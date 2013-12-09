/*
 * Copyright 2013 Pascal Voitot (@mandubian)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mandubian
package actorroom

import scala.concurrent._
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor._
import akka.pattern.{ask, pipe}
import akka.util.Timeout

import play.api._
import play.api.mvc._
import play.api.libs.iteratee._
import play.api.libs.json._
import play.api.libs.concurrent._
import play.api.mvc.WebSocket.FrameFormatter

// Implicits
import play.api.libs.concurrent.Execution.Implicits._

sealed trait Message

// public receiver messages
case class   Received[A](from: String, payload: A) extends Message

// public sender messages
/** Sender actor is initialized by Supervisor */
case class   Init(id: String, receiverActor: ActorRef)
/** Sends a message from a member to another member */
case class   Send[A](from: String, to: String, payload: A) extends Message
/** Broadcasts a message from a member */
case class   Broadcast[A](from: String, payload: A) extends Message
/** member with ID has connected */
case class   Connected(id: String) extends Message
/** member with ID has disconnected */
case class   Disconnected(id: String) extends Message

// Administration messages...
// They should be used only when ocerriding default mechanisms

// Websocket specific
case class   ConnectWS[A](id: String, receiverProps: Props, senderProps: Props) extends Message
case class   ConnectedWS[A](id: String, receiver: ActorRef, enumerator: Enumerator[A]) extends Message

// Bot specific
case class   ConnectBot(id: String, receiverProps: Props, senderProps: Props) extends Message
case class   ConnectedBot[A](id: String, member: Member) extends Message

case class   BroadcastMessage(payload: Message) extends Message
case class   Forbidden(id: String, err: String) extends Message

// Members
case class   Member(id: String, val receiver: ActorRef, val sender: ActorRef) extends Message
case object  ListMemberIds extends Message
case class   MemberIds(ids: Seq[String]) extends Message
case class   GetMember(id: String) extends Message
case class   Unknown(id: String) extends Message


/** Typeclass to format administration messages according to your payload type:
  * - connected
  * - disconnected
  * - error message
  */
trait AdminMsgFormatter[Payload] {
  def connected(id: String): Payload
  def disconnected(id: String): Payload
  def error(id: String, msg: String): Payload
}

/** Default implicits AdminMsgFormatter */
object AdminMsgFormatter{

  implicit val string = new AdminMsgFormatter[String]{
    def connected(id: String) = s"connected: $id"
    def disconnected(id: String) = s"disconnected: $id"
    def error(id: String, msg: String) = s"id: $id - error: $msg"
  }

  implicit val json = new AdminMsgFormatter[JsValue]{
    def connected(id: String) = Json.obj("kind" -> "connected", "id" -> id)
    def disconnected(id: String) = Json.obj("kind" -> "disconnected", "id" -> id)
    def error(id: String, msg: String) = Json.obj("kind" -> "error", "id" -> id, "error" -> msg)
  }
}

/** Room managing all connected members
  * - 1 member can be created as a websocket or a bot (a fake member)
  * - 1 member are constituted of a:
  *    - 1 receiver actor
  *    - 1 sender actor
  * - 1 supervisor actor supervises all actors and manages routing/broadcasting/disconnecting
  */
class Room(supervisorProps: Props)(implicit app: Application) {

  lazy val supervisor = Akka.system.actorOf(supervisorProps)

  def websocket[Receiver <: Actor : scala.reflect.ClassTag, Payload](id: String)
    (implicit frameFormatter: FrameFormatter[Payload],
              msgFormatter: AdminMsgFormatter[Payload]): WebSocket[Payload] =
    websocket(_ => id, Props[Receiver])

  def websocket[Receiver <: Actor : scala.reflect.ClassTag, Payload](f: RequestHeader => String)
    (implicit frameFormatter: FrameFormatter[Payload],
              msgFormatter: AdminMsgFormatter[Payload]): WebSocket[Payload] =
    websocket(f, Props[Receiver])

  def websocket[Payload](f: RequestHeader => String, receiverProps: Props)
    (implicit frameFormatter: FrameFormatter[Payload],
              msgFormatter: AdminMsgFormatter[Payload]): WebSocket[Payload] = {

    val senderProps = Props(classOf[WebSocketSender[Payload]], msgFormatter)
    websocket[Payload](f, receiverProps, senderProps)
  }

  def websocket[Payload](f: RequestHeader => String, receiverProps: Props, senderProps: Props)
    (implicit frameFormatter: FrameFormatter[Payload],
              msgFormatter: AdminMsgFormatter[Payload]): WebSocket[Payload] = {

    implicit val timeout = Timeout(1 second)

    WebSocket.async[Payload]{ request =>
      val id = f(request)
      (supervisor ? ConnectWS[Payload](id, receiverProps, senderProps)).map {

        case c: ConnectedWS[Payload] =>
          val iteratee = Iteratee.foreach[Payload] { event =>
            c.receiver ! Received(id, event)
          }.map { _ =>
            supervisor ! Disconnected(id)
          }
          (iteratee, c.enumerator)

        case Forbidden(id, error) =>
          // Connection error

          // A finished Iteratee sending EOF
          val iteratee = Done[Payload, Unit]((), Input.EOF)

          // Send an error and close the socket
          val enumerator = Enumerator[Payload](
            msgFormatter.error(id, s"id $id already connected")
          ).andThen(Enumerator.enumInput(Input.EOF))

          (iteratee, enumerator)
      }
    }
  }

  def bot[Payload](id: String)
    (implicit msgFormatter: AdminMsgFormatter[Payload]): Future[Member] = 
    bot(id, Props[BotReceiver[Payload]])

  def bot[Payload](
    id: String,
    senderProps: Props
  )(implicit msgFormatter: AdminMsgFormatter[Payload]): Future[Member] = {
    val receiverProps = Props(classOf[BotReceiver[Payload]], msgFormatter)
    bot(id, receiverProps, senderProps)
  }

  def bot[Payload](
    id: String,
    receiverProps: Props,
    senderProps: Props
  ): Future[Member] = {

    implicit val timeout = Timeout(1 second)

    (supervisor ? ConnectBot(id, receiverProps, senderProps)).map {

      case ConnectedBot(_, member) => member

      case Forbidden(_, error)   => throw new RuntimeException(error)
    }
  }

  def members: Future[Seq[String]] = {
    implicit val timeout = Timeout(1 second)

    (supervisor ? ListMemberIds).map {
      case MemberIds(ids) => ids
    }
  }

  def getMember(id: String): Future[Member] = {
    implicit val timeout = Timeout(1 second)

    (supervisor ? GetMember(id)).map {
      case m: Member => m
      case Unknown(id) => throw new RuntimeException(s"unknown $id")
    }

  }

  def broadcast[Payload](from: String, payload: Payload) = supervisor ! Broadcast(from, payload)

  def send[Payload](from: String, to: String, payload: Payload) = supervisor ! Send(from, to, payload)

  def stop = supervisor ! PoisonPill
}

object Room {
	def apply()(implicit app: Application) =
    new Room(Props(classOf[Supervisor]))(app)

  def apply(supervisorProps: Props)(implicit app: Application) =
    new Room(supervisorProps)(app)

  def async[A]( fws: => Future[WebSocket[A]] )(implicit frameFormatter: WebSocket.FrameFormatter[A]) = {
    WebSocket[A](h => (e, i) => {
      fws onSuccess { case ws => ws.f(h)(e, i) }
    })
  }
}

/** The default actor sender for WebSockets */
class WebSocketSender[Payload](implicit msgFormatter: AdminMsgFormatter[Payload]) extends Actor {
  var channel: Option[Concurrent.Channel[Payload]] = None

  def receive = {
    case Init(id, receiverActor) =>
      val me = self
      val enumerator = Concurrent.unicast[Payload]{ c =>
        channel = Some(c)

        me ! Connected(id)
      }
      sender ! ConnectedWS[Payload](id, receiverActor, enumerator)

    case s: Send[Payload]        => channel.foreach(_.push(s.payload))

    case b: Broadcast[Payload]   => channel.foreach(_.push(b.payload))

    case Connected(id) =>
      context.parent ! Broadcast[Payload](id, msgFormatter.connected(id))

    case Disconnected(id) =>
      context.parent ! Broadcast(id, msgFormatter.disconnected(id))
      play.Logger.info(s"Disconnected ID:$id")
  }

  override def postStop() {
    channel.foreach(_.push(Input.EOF))
  }

}

/** The default actor sender for Bots */
class BotSender[Payload](implicit msgFormatter: AdminMsgFormatter[Payload]) extends Actor {

  def receive = {
    case s =>
      play.Logger.info(s"Bot should have sent ${s}")

  }

}

/** The default actor receiver for Bots */
class BotReceiver[Payload] extends Actor {

  def receive = {
    case r: Received[Payload] =>
      play.Logger.info(s"Bot ${r.from} broadcasting ${r.payload}")
      context.parent ! Broadcast[Payload](r.from, r.payload)
  }

}

/** The supervisor managing all members */
class Supervisor extends Actor {
  var members = Map.empty[String, Member]

  def receive = {
    case ConnectWS(id, receiverProps, senderProps) =>
      if(members.contains(id)) sender ! Forbidden(id, "id already connected")
      else {
        implicit val timeout = Timeout(1 second)
        val receiveActor = context.actorOf(receiverProps, id+"-receiver")
        val sendActor = context.actorOf(senderProps, id+"-sender")

        val c = (sendActor ? Init(id, receiveActor)).map{
          case c: ConnectedWS[_] =>
            play.Logger.debug(s"Connected Member with ID:$id")
            members = members + (id -> Member(id, receiveActor, sendActor))
            c
        }

        c pipeTo sender
      }

    case ConnectBot(id, receiverProps, senderProps) =>
      if(members.contains(id)) sender ! Forbidden(id, "id already connected")
      else {
        val receiveActor = context.actorOf(receiverProps, id+"-receiver")
        val sendActor = context.actorOf(senderProps, id+"-sender")
        play.Logger.debug(s"Connected Bot with ID:$id")
        members = members + (id -> Member(id, receiveActor, sendActor))

        self ! Connected(id)

        sender ! ConnectedBot(id, Member(id, receiveActor, sendActor))
      }

    case s: Send[_] =>
      members.get(s.to).foreach { m => m.sender forward s }

    case b: Broadcast[_] =>
      members.foreach{
        case (id, m) => m.sender forward b

        case _ => ()
      }

    case b: BroadcastMessage =>
      members.foreach{
        case (id, m) => m.sender forward b.payload

        case _ => ()
      }

    case Disconnected(id) =>
      members.get(id).foreach{ m =>
        members = members - id
        m.sender ! Disconnected(id)
        m.receiver ! PoisonPill
        m.sender ! PoisonPill
      }

    case GetMember(id) =>
      members.get(id).map { m => sender ! m }.getOrElse(Unknown(id))

    case ListMemberIds =>
      sender ! MemberIds(members.map(_._1).toSeq)
  }
}