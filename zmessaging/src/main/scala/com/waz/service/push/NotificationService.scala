/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.service.push

import com.waz.ZLog.LogTag
import com.waz.api.Message
import com.waz.api.NotificationsHandler.NotificationType
import com.waz.api.NotificationsHandler.NotificationType._
import com.waz.content._
import com.waz.log.ZLog2._
import com.waz.model.GenericContent.{LastRead, MsgDeleted, MsgEdit, Reaction, Text}
import com.waz.model.UserData.ConnectionStatus
import com.waz.model._
import com.waz.service.ZMessaging.accountTag
import com.waz.service._
import com.waz.threading.Threading
import com.waz.utils._
import com.waz.utils.events.{EventContext, Signal}
import com.waz.zms.NotificationsAndroidService
import org.threeten.bp
import org.threeten.bp.{Clock, Instant}

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * A trait representing some controller responsible for displaying notifications in the UI. It is expected that this
  * controller is a global singleton
  */
trait NotificationUiController {
  /**
    * A call to the UI telling it that it has notifications to display. This needs to be a future so that we can wait
    * for the displaying of notifications before finishing the event processing pipeline. Upon completion of the future,
    * we can also mark these notifications as displayed.
    * @param account the account the notifications are for
    * @param shouldBeSilent whether the notifications passed up should make a sound or not //TODO move toUI?
    * @param notifications the list notifications to display
    * @return a Future that should enclose the display of notifications to the UI
    */
  def showNotifications(account: UserId, shouldBeSilent: Boolean, notifications: Set[NotificationData]): Future[Unit]

  /**
    * To be called by the UI when any conversations in a given account are visible to the user. When visible, the user
    * should see in some way that they have notifications for that conversation, so we can automatically dismiss the
    * notifications for them.
    */
  def notificationsSourceVisible: Signal[Map[UserId, Set[ConvId]]]
}

class NotificationService(selfUserId:      UserId,
                          messages:        MessagesStorage,
                          storage:         NotificationStorage,
                          convs:           ConversationStorage,
                          pushService:     PushNotificationService,
                          uiController:    NotificationUiController,
                          clock:           Clock) {

  import Threading.Implicits.Background
  implicit lazy val logTag: LogTag = accountTag[NotificationService](selfUserId)


  uiController.notificationsSourceVisible { sources =>
    sources.get(selfUserId).foreach(onConversationsVisible)
  } (EventContext.Global) //TODO account event context

//  val alarmService = Option(context) match {
//    case Some(c) => Some(c.getSystemService(Context.ALARM_SERVICE).asInstanceOf[AlarmManager])
//    case _ =>
//      warn(l"No context, could not start alarm service")
//      None

  /**
    * Removes all notifications that are being displayed for the given set of input conversations and updates the UI
    * with all remaining notifications
    */
  def onConversationsVisible(convs: Set[ConvId]): Future[Unit] = {
    for {
      nots               <- storage.list().map(_.toSet)
      (toRemove, toShow) = nots.partition(n => convs.contains(n.conv))
      _ <- uiController.showNotifications(selfUserId, shouldBeSilent = true, toShow)
      _ <- storage.removeAll(toRemove.map(_.id))
    } yield {}
  }

  //For UI to decide if it should make sounds or not
  //TODO, can we move to the UI?
  val otherDeviceActiveTime = Signal(Instant.EPOCH)

  private def shouldBeSilent() = otherDeviceActiveTime.head.map { t =>
    val timeDiff = clock.instant.toEpochMilli - t.toEpochMilli
    timeDiff < NotificationsAndroidService.checkNotificationsTimeout.toMillis
  }

  val messageNotificationEventsStage = EventScheduler.Stage[MessageEvent]({ (c, events) =>
    events.foreach {
      case GenericMessageEvent(_, _, _, GenericMessage(_, LastRead(_, _))) =>
        otherDeviceActiveTime ! clock.instant
      case _ =>
    }

    for {
      Some(conv)           <- convs.getByRemoteId(c)
      (undoneLikes, likes) <- getReactionChanges(events)
      drift                <- pushService.beDrift.head

      currentNotifications <- storage.list().map(_.toSet.filter(_.conv == conv.id)) //only handle notifications for the current conversation
      msgNotifications     <- getMessageNotifications(conv, events, drift)

      (afterEditsApplied, beforeEditsApplied) = applyEdits(currentNotifications ++ msgNotifications, events)

      deleted = events.collect { case GenericMessageEvent(_, _, _, GenericMessage(_, MsgDeleted(_, msg))) => NotId(msg) }.toSet

      toShow = (afterEditsApplied ++ likes).filterNot(n => (undoneLikes ++ deleted).contains(n.id))
      toRemove = undoneLikes ++ beforeEditsApplied ++ deleted

      _ <- pushNotificationsToUi(toShow, toRemove)
    } yield {}
  })

  val connectionNotificationEventStage = EventScheduler.Stage[Event]({ (_, events) =>
    val toShow = events.collect {
      case UserConnectionEvent(_, _, userId, msg, ConnectionStatus.PendingFromOther, time, name) =>
        NotificationData(NotId(CONNECT_REQUEST, userId), msg.getOrElse(""), ConvId(userId.str), userId, CONNECT_REQUEST, time, userName = name)
      case UserConnectionEvent(_, _, userId, _, ConnectionStatus.Accepted, time, name) =>
        NotificationData(NotId(CONNECT_ACCEPTED, userId), "", ConvId(userId.str), userId, CONNECT_ACCEPTED, time, userName = name)
      case ContactJoinEvent(userId, _) =>
        verbose(l"ContactJoinEvent")
        NotificationData(NotId(CONTACT_JOIN, userId), "", ConvId(userId.str), userId, CONTACT_JOIN)
    }
    pushNotificationsToUi(toShow.toSet, Set.empty)
  })

  private def pushNotificationsToUi(toShow: Set[NotificationData], toRemove: Set[NotId]) = {
    for {
      notificationSourceVisible <- uiController.notificationsSourceVisible.head
      convs <- convs.getAll(toShow.map(_.conv)).map(_.collect { case Some(c) => c.id -> c }.toMap)
      (filteredShow, seen) = toShow.partition { n =>
        //Filter notifications for those coming from other users, and that have come after the last-read time.
        //Note that for muted conversations, the last-read time is set to Instant.MAX, so they can never come after.

        val sourceVisible = notificationSourceVisible.get(selfUserId).exists(_.contains(n.conv))
        n.user != selfUserId &&
          convs(n.conv).lastRead.isBefore(n.time) &&
          convs(n.conv).cleared.forall(_.isBefore(n.time)) &&
          !sourceVisible
      }

      silent <- shouldBeSilent()
      _ <- uiController.showNotifications(selfUserId, silent, filteredShow)
      _ <- storage.insertAll(filteredShow.map(_.copy(hasBeenDisplayed = true)))
      _ <- storage.removeAll(toRemove ++ seen.map(_.id))
    } yield {}
  }

  private def getMessageNotifications(conv: ConversationData, events: Vector[Event], drift: bp.Duration) = {
    val eventTimes = events.collect { case e: MessageEvent => e.time }
    if (eventTimes.nonEmpty) {
      for {
        msgs     <- messages.findMessagesFrom(conv.id, eventTimes.min)
        quoteIds <- messages
          .getAll(msgs.filter(!_.hasMentionOf(selfUserId)).flatMap(_.quote))
          .map(_.flatten.filter(_.userId == selfUserId).map(_.id).toSet)

      } yield msgs.flatMap { msg =>

          import Message.Type._

          val tpe = msg.msgType match {
            case TEXT | TEXT_EMOJI_ONLY | RICH_MEDIA => Some(NotificationType.TEXT)
            case KNOCK        => Some(NotificationType.KNOCK)
            case ASSET        => Some(NotificationType.ASSET)
            case LOCATION     => Some(NotificationType.LOCATION)
            case RENAME       => Some(NotificationType.RENAME)
            case MISSED_CALL  => Some(NotificationType.MISSED_CALL)
            case ANY_ASSET    => Some(NotificationType.ANY_ASSET)
            case AUDIO_ASSET  => Some(NotificationType.AUDIO_ASSET)
            case VIDEO_ASSET  => Some(NotificationType.VIDEO_ASSET)
            case MEMBER_JOIN  =>
              if (msg.members == Set(msg.userId)) None // ignoring auto-generated member join event when user accepts connection
              else Some(NotificationType.MEMBER_JOIN)
            case MEMBER_LEAVE => Some(NotificationType.MEMBER_LEAVE)
            case _ => None
          }

          tpe.map { tp =>
            NotificationData(
              id        = NotId(msg.id),
              msg       = if (msg.isEphemeral) "" else msg.contentString, msg.convId,
              user      = msg.userId,
              msgType   = tp,
              //TODO do we ever get RemoteInstant.Epoch?
              time      = if (msg.time == RemoteInstant.Epoch) msg.localTime.toRemote(drift) else msg.time,
              ephemeral = msg.isEphemeral,
              mentions  = msg.mentions.flatMap(_.userId),
              isReply   = msg.quote.exists(quoteIds(_))
            )
          }
        }.toSet
    } else Future.successful(Set.empty)
  }

  private def applyEdits(currentNotifications: Set[NotificationData], events: Vector[Event]) = {
    val edits = events
      .collect { case GenericMessageEvent(_, _, _, GenericMessage(newId, MsgEdit(id, Text(msg, _, _, _)))) => (id, (newId, msg)) }
      .toMap

    val (editsApplied, afterEdits) = edits.foldLeft((currentNotifications.map(n => (n.id, n)).toMap, Set.empty[NotId])) {
      case ((edited, toRemove), (oldId, (newId, newContent))) =>
        edited.get(NotId(oldId.str)) match {
          case Some(toBeEdited) =>
            val newNotId = NotId(newId.str)
            val updated = toBeEdited.copy(id = newNotId, msg = newContent)

            ((edited - toBeEdited.id) + (newNotId -> updated), toRemove + toBeEdited.id)

          case _ => (edited, toRemove)
        }
    }

    (editsApplied.values.toSet, afterEdits)
  }

  private def getReactionChanges(events: Vector[Event]) = {
    val reactions = events.collect {
      case GenericMessageEvent(_, time, from, GenericMessage(_, Reaction(msg, action))) if from != selfUserId => Liking(msg, from, time, action)
    }

    messages.getAll(reactions.map(_.message)).map(_.flatten).map { msgs =>

      val msgsById = msgs.map(m => m.id -> m).toMap
      val convsByMsg = msgs.iterator.by[MessageId, Map](_.id).mapValues(_.convId)
      val myMsgs = msgs.collect { case m if m.userId == selfUserId => m.id }.toSet
      val rs = reactions.filter(r => myMsgs.contains(r.message)).sortBy(_.timestamp)

      val (toRemove, toAdd) = rs.foldLeft((Set.empty[(MessageId, UserId)], Map.empty[(MessageId, UserId), Liking])) {
        case ((rs, as), r @ Liking(_, _, _, Liking.Action.Like))  => (rs - r.id, as + (r.id -> r))
        case ((rs, as), r @ Liking(_, _, _, Liking.Action.Unlike)) => (rs + r.id, as - r.id)
      }

      (toRemove.map(NotId(_)), toAdd.values.map { r =>
        val msg = msgsById(r.message)
        NotificationData(
          id      = NotId(r.id),
          msg     = msg.contentString,
          conv    = convsByMsg(r.message),
          user    = r.user,
          msgType = LIKE,
          time    = r.timestamp,
          likedContent = Some(msg.msgType match {
            case Message.Type.ASSET           => LikedContent.PICTURE
            case Message.Type.TEXT |
                 Message.Type.TEXT_EMOJI_ONLY => LikedContent.TEXT_OR_URL
            case _                            => LikedContent.OTHER
          })
        )
      }.toSet)
    }
  }
}


object NotificationService {

  //var for tests
  var ClearThrottling = 3.seconds
}
