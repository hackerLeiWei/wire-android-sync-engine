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
package com.waz.service

import android.text.TextUtils
import com.waz.ServerConfig
import com.waz.service.BackendConfig.FirebaseOptions
import com.waz.utils.wrappers.URI

case class BackendConfig(baseUrl: URI, websocketUrl: String, firebaseOptions: FirebaseOptions, environment: String) {
  val pushSenderId = firebaseOptions.pushSenderId
}

object BackendConfig {

  case class FirebaseOptions(pushSenderId: String, appId: String, apiKey: String)

  //This information can be found in downloadable google-services.json file from the BE console.
  private var StagingFirebaseOptions: FirebaseOptions = _
  // FirebaseOptions("723990470614", "1:723990470614:android:9a1527f79aa62284", "AIzaSyAGCoJGUtDBLJJiQPLxHQRrdkbyI0wlbo8")
  var ProdFirebaseOptions: FirebaseOptions = _
  //FirebaseOptions("782078216207", "1:782078216207:android:d3db2443512d2055", "AIzaSyBdYVv2f-Y7JJmHVmDNCKgWvX6Isa8rAGA")

  var StagingBackend: BackendConfig = _
  // BackendConfig(URI.parse("https://staging-nginz-https.zinfra.io"), "https://staging-nginz-ssl.zinfra.io/await", StagingFirebaseOptions, "staging")
  var ProdBackend: BackendConfig = _
  //BackendConfig(URI.parse("https://prod-nginz-https.wire.com"),     "https://prod-nginz-ssl.wire.com/await",     ProdFirebaseOptions,    "prod")

  def byName: Map[String, BackendConfig] = Seq(StagingBackend, ProdBackend).map(b => b.environment -> b).toMap

  def getStagingFirebaseOptions() = StagingFirebaseOptions

  def getProdFirebaseOptions() = ProdFirebaseOptions

  def getStagingBackend() = StagingBackend

  def getProdBackend() = ProdBackend

  def apply(baseUrl: String): BackendConfig = {
    if (StagingFirebaseOptions==null){
      throw new NullPointerException("BackendConfig#StagingFirebaseOptions is NULL")
    }
    BackendConfig(URI.parse(baseUrl), "", StagingFirebaseOptions, "")
  } // XXX only use for testing!


  /**
    * [[ServerConfig.setParams()]] 或 [[ServerConfig.setAllParams()]]需要被调用
    *
    * @param project_number
    * @param mobilesdk_app_id
    * @param current_key
    */
  def initFirebase(project_number: String, mobilesdk_app_id: String, current_key: String): Unit = {
    if (TextUtils.isEmpty(ServerConfig.getBASE_URL_HTTPS)) {
      throw new NullPointerException("[[ServerConfig.setParams()]] 或 [[ServerConfig.setAllParams()]]需要被调用")
    } else {
      ProdFirebaseOptions = FirebaseOptions(project_number, mobilesdk_app_id, current_key)
      StagingFirebaseOptions = FirebaseOptions(project_number, mobilesdk_app_id, current_key)
      StagingBackend = BackendConfig(URI.parse(ServerConfig.getBASE_URL_HTTPS()), ServerConfig.getBASE_URL_HTTPS() + "/await", StagingFirebaseOptions, "staging")
      ProdBackend = BackendConfig(URI.parse(ServerConfig.getBASE_URL_HTTPS()), ServerConfig.getBASE_URL_HTTPS() + "/await", ProdFirebaseOptions, "prod")
    }
  }

}
