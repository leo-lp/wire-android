/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.common.controllers

import android.app.Activity
import com.waz.log.BasicLogging.LogTag.DerivedLogTag
import com.waz.model.ConvId
import com.wire.signals.Signal
import com.waz.utils.wrappers.{AndroidURIUtil, Intent, URI => URIWrapper}
import com.waz.zclient.Intents._
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.{Injectable, Injector, WireContext}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.collection.JavaConverters._
import com.waz.zclient.pages.main.conversation.AssetIntentsManager

class SharingController(implicit injector: Injector, wContext: WireContext)
  extends Injectable with DerivedLogTag {

  import SharingController._

  import com.waz.threading.Threading.Implicits.Background

  val sharableContent     = Signal(Option.empty[SharableContent])
  val ephemeralExpiration = Signal(Option.empty[FiniteDuration])

  private val targetConvs = Signal(Seq.empty[ConvId])

  def onContentShared(activity: Activity, convs: Seq[ConvId]): Unit = {
    targetConvs ! convs
    val intent = SharingIntent(wContext)
    intent.setAction(android.content.Intent.ACTION_OPEN_DOCUMENT)
    intent.addCategory(android.content.Intent.CATEGORY_OPENABLE)
    Option(activity).foreach(_.startActivity(intent))
  }

  def sendContent(intent: Intent, activity: Activity): Future[Seq[ConvId]] = {
    val convController = inject[ConversationController]

    def send(content: SharableContent, convs: Seq[ConvId], expiration: Option[FiniteDuration]): Future[Unit] =
      content match {
        case NewContent =>
          convController.switchConversation(convs.head)
        case TextContent(t) =>
          convController.sendTextMessage(convs, t, Nil, None, Some(expiration)).map(_ => ())
        case uriContent if uriContent.uris.nonEmpty =>
          val uris = uriContent.uris.map(AndroidURIUtil.unwrap)
          intent.setAction(android.content.Intent.ACTION_OPEN_DOCUMENT)
          AssetIntentsManager.grantUrisPermissions(activity, intent, uris.asJava)
          convController.sendAssetMessages(uriContent.uris.map(URIWrapper.toJava), Some(expiration), convs)
        case _ =>
            Future.successful(Nil)
      }

    for {
      Some(content) <- sharableContent.head
      convs         <- targetConvs.head
      expiration    <- ephemeralExpiration.head
      _             <- send(content, convs, expiration)
      _             =  resetContent()
    } yield convs
  }

  def getSharedText(convId: ConvId): String = sharableContent.currentValue.flatten match {
    case Some(TextContent(t)) if targetConvs.currentValue.exists(_.contains(convId)) => t
    case _ => null
  }

  private def resetContent() = {
    sharableContent     ! None
    targetConvs         ! Seq.empty
    ephemeralExpiration ! None
  }

  def publishTextContent(text: String): Unit =
    this.sharableContent ! Some(TextContent(text))
}

object SharingController {

  sealed trait SharableContent {
    val uris: Seq[URIWrapper]
  }

  case object NewContent extends SharableContent { override val uris = Seq.empty }

  case class TextContent(text: String) extends SharableContent { override val uris = Seq.empty }

  case class FileContent(uris: Seq[URIWrapper]) extends SharableContent

  case class ImageContent(uris: Seq[URIWrapper]) extends SharableContent
}
