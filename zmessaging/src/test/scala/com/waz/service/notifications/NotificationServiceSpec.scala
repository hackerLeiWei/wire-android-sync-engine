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
package com.waz.service.notifications

import com.waz.api.Message
import com.waz.api.NotificationsHandler.NotificationType
import com.waz.content.{ConversationStorage, MessagesStorage, NotificationStorage}
import com.waz.model.GenericContent.{MsgEdit, Text}
import com.waz.model.GenericMessage.TextMessage
import com.waz.model._
import com.waz.service.push.{NotificationService, NotificationUiController, PushNotificationService}
import com.waz.specs.AndroidFreeSpec
import com.waz.utils.events.Signal
import com.waz.utils._
import org.threeten.bp.Duration

import scala.collection.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

class NotificationServiceSpec extends AndroidFreeSpec {

  val messages      = mock[MessagesStorage]
  val storage       = mock[NotificationStorage]
  val convs         = mock[ConversationStorage]
  val pushService   = mock[PushNotificationService]
  val uiController  = mock[NotificationUiController]

  val beDrift = Signal(Duration.ZERO)
  val uiNotificationsSourceVisible = Signal(Map.empty[UserId, Set[ConvId]])

  scenario("Process basic text message notifications") {

    //advance the clock to avoid weird problems around the epoch (e.g.)
    clock + 24.hours

    val rConvId = RConvId("conv")
    val conv = ConversationData(remoteId = rConvId)

    val lastEventTime = RemoteInstant.apply(clock.instant())

    val content = TextMessage("abc")
    val from = UserId("User1")
    val event = GenericMessageEvent(rConvId, lastEventTime, from, content)

    (convs.getByRemoteId _).expects(rConvId).once().returning(Future.successful(Some(conv)))
    (convs.getAll _).expects(Set(conv.id)).once().returning(Future.successful(Seq(Some(conv))))
    (storage.list _).expects().once().returning(Future.successful(Seq.empty))
    (messages.getAll _).expects(Vector.empty).returning(Future.successful(Seq.empty))
    (messages.findMessagesFrom _).expects(conv.id, lastEventTime).returning(Future.successful(
      IndexedSeq(
        MessageData(
          MessageId(content.messageId),
          conv.id,
          msgType = Message.Type.TEXT,
          protos = Seq(content),
          userId = from,
          time   = lastEventTime
        )
      )
    ))
    (uiController.showNotifications _).expects(account1Id, false, *).onCall { (_, _, nots) =>
      nots.size shouldEqual 1
      nots.head.msg shouldEqual "abc"
      nots.head.hasBeenDisplayed shouldEqual false
      Future.successful({})
    }
    (storage.insertAll _).expects(*).returning(Future.successful(Set.empty)) //return not important
    (storage.removeAll _).expects(*).returning(Future.successful(Set.empty)) //return not important

    val service = getService()

    await(service.messageNotificationEventsStage(rConvId, Vector(event)))

  }

  scenario("Apply multiple message edit events to previous notifications") {
    //advance the clock to avoid weird problems around the epoch (e.g.)
    clock + 24.hours

    val rConvId = RConvId("conv")
    val conv = ConversationData(remoteId = rConvId)

    val from = UserId("User1")

    val origEventTime = RemoteInstant.apply(clock.instant())
    val edit1EventTime = RemoteInstant.apply(clock.instant() + 10.seconds)
    val edit2EventTime = RemoteInstant.apply(clock.instant() + 10.seconds)

    val originalContent = GenericMessage(Uid("messageId"), Text("abc"))

    val editContent1 = GenericMessage(Uid("edit-id-1"), MsgEdit(MessageId(originalContent.messageId), Text("def")))
    val editEvent1 = GenericMessageEvent(rConvId, edit1EventTime, from, editContent1)

    val editContent2 = GenericMessage(Uid("edit-id-2"), MsgEdit(MessageId(editContent1.messageId), Text("ghi")))
    val editEvent2 = GenericMessageEvent(rConvId, edit2EventTime, from, editContent2)

    val originalNotification = NotificationData(
      id = NotId(originalContent.messageId),
      msg = "abc",
      conv = conv.id,
      user = from,
      msgType = NotificationType.TEXT,
      time = origEventTime,
      hasBeenDisplayed = true
    )

    val updatedNotification = originalNotification.copy(
      id = NotId(editContent2.messageId),
      msg = "ghi"
    )

    (convs.getByRemoteId _).expects(rConvId).once().returning(Future.successful(Some(conv)))
    (convs.getAll _).expects(Set(conv.id)).once().returning(Future.successful(Seq(Some(conv))))
    (storage.list _).expects().once().returning(Future.successful(Seq(originalNotification)))
    (messages.getAll _).expects(Vector.empty).returning(Future.successful(Seq.empty))
    (messages.findMessagesFrom _).expects(conv.id, edit1EventTime).returning(Future.successful(IndexedSeq.empty))
    (uiController.showNotifications _).expects(account1Id, false, *).onCall { (_, _, nots) =>
      nots.head shouldEqual updatedNotification
      Future.successful({})
    }
    (storage.insertAll _).expects(Set(updatedNotification)).returning(Future.successful(Set.empty)) //return not important
    (storage.removeAll _).expects(Set(NotId("messageId"), NotId("edit-id-1"))).returning(Future.successful(Set.empty)) //return not important

    val service = getService()

    await(service.messageNotificationEventsStage(rConvId, Vector(editEvent1, editEvent2)))
  }

  def getService() = {
    (pushService.beDrift _).expects().anyNumberOfTimes().returning(beDrift)
    (uiController.notificationsSourceVisible _).expects().anyNumberOfTimes().returning(uiNotificationsSourceVisible)

    new NotificationService(account1Id, messages, storage, convs, pushService, uiController, clock)
  }


}
