# Play Actor Room

####A Room manager for Play Framework 2.2 based on Websockets & Bots


**Actor-Room** makes it easy to:

- create any group of connected entities (people or not) (chatroom, forum, broadcast pivot...)
- manage connections, disconnections, broadcast, targetted message through actor and nothing else.

For now, members can be:
- websocket endpoints through actors without taking care of Iteratees/Enumerators...
- Bots to simulate members

## Reminders on websockets in Play

Here is the function Play provides to create a websocket:

```scala
def async[A](
  f: RequestHeader => Future[(Iteratee[A, _], Enumerator[A])]
)(implicit frameFormatter: FrameFormatter[A]): WebSocket[A]
```

A websocket is a persistent bi-directional channel of communication (in/out) and is created with:

- an `Iteratee[A, _]` to manage all frames received by the websocket endpoint
- an `Enumerator[A]` to send messages through the websocket
- an implicit `FrameFormatter[A]` to parse frame content to type `A` (Play provides default FrameFormatter for String or JsValue)

Here is how you traditionally create a websocket endpoint in Play:

```scala
object MyController extends Controller {
    def connect = Websocket.async[JsValue]{ rh =>
        // the iteratee to manage received messages
        val iteratee = Iteratee.foreach[JsValue]( js => ...)

        // the enumerator to be able to send messages
        val enumerator = // generally a PushEnumerator
        (iteratee, enumerator)
    }
}
```

Generally, the `Enumerator[A]` is created using `Concurrent.broadcast[A]` and `Concurrent.unicast[A]` which are very powerful tools but not so easy to understand exactly (the edge-cases of connection close, errors are always tricky).

You often want to:

- manage multiple client connections at the same time
- parse messages received from websockets,
- do something with the message payload
- send messages to a given client
- broadcast messages to all connected members
- create bots to be able to simulate fake connected members
- etc...

To do that in Play non-blocking/async architecture, you often end developing an Actor topology managing all events/messages on top of the previous `Iteratee/Enumerator`. 

The `Iteratee/Enumerator` is quite generic but always not so easy to write.

The actor topology is quite generic because there are administration messages that are almost always the same:

- Connection/Forbidden/Disconnection
- Broadcast/Send

<br/>
> **Actor Room** is a helper managing all of this for you. 
> So you can just focus on message management using actors and nothing else. It provides all default behaviors and all behaviors can be overriden if needed. It exposes only actors and nothing else.

<br/>
*The code is based on the chatroom sample (and a cool sample by Julien Tournay) from Play Framework pushed far further and in a more generic way.*

## What is Actor Room?

An actor room manages a group of connected members which are supervised by a supervisor

### Member = 2 actors (receiver/sender)

Each member is represented by 2 actors (1 receiver & 1 sender):

- **You MUST create at least a Receiver Actor because it's your job to manage your own message format**

- The Sender Actor has a default implementation but you can override it.

### Supervisor = 1 actor

All actors are managed by 1 supervisor which have two roles:

- Creates/supervises all receiver/sender actors

- Manages administration messages (routing, forwarding, broadcasting etc...)


# Code sample step by step

## Create the Actor Room

```scala
  // default constructor
  val room = Room()

  // constructor with custom supervisor
  // custom supervisor are described later
  val room = Room(Props(classOf[CustomSupervisor]))
```

The room creates the Supervisor actor for you and delegates the creation of receiver/sender actors to it.

If you want to broadcast a message or target a precise member, you should use the supervisor.

```scala
  room.supervisor ! Broadcast("fromId", Json.obj("foo" -> "bar"))
  room.supervisor ! Send("fromId", "toId", Json.obj("foo" -> "bar"))
```

> You can manage several rooms in the same project.

## Create the mandatory Receiver Actor

There is only one message to manage:

```scala
/** Message received and parsed to type A
  * @param from the ID of the sender
  * @param payload the content of the message
  */
case class Received[A](from: String, payload: A) extends Message
```

If your websocket frames contain Json, then it should be `Received[JsValue]`.

You just have to create a simple actor:

```scala
// Create an actor to receive messages from websocket
class Receiver extends Actor {
  def receive = {
    // Received(fromId, js) is the only Message to manage in receiver
    case Received(from, js: JsValue) =>
      (js \ "msg").asOpt[String] match {
        case None =>
          play.Logger.error("couldn't msg in websocket event")

        case Some(s) =>
          play.Logger.info(s"received $s")
          // broadcast message to all connected members
          context.parent ! Broadcast(from, Json.obj("msg" -> s))
      }
  }
}
```

Please note the Receiver Actor is supervised by the `Supervisor` actor. So, within the Receiver Actor, `context.parent` is the `Supervisor` and you can use it to send/broadcast message as following:

```scala
context.parent ! Send(fromId, toId, mymessage)
context.parent ! Broadcast(fromId, mymessage)

// The 2 messages
/** Sends a message from a member to another member */
case class   Send[A](from: String, to: String, payload: A) extends Message

/** Broadcasts a message from a member */
case class   Broadcast[A](from: String, payload: A) extends Message

```


## Create your Json websocket endpoint

Please note that each member is identified by a string that you define yourself.

import org.mandubian.actorroom._

```scala
class Receiver extends Actor {
  def receive = {
    ...
  }
}

object Application extends Controller {
  val room = Room()

  /** websocket requires :
    * - the type of the Receiver actor
    * - the type of the payload
    */
  def connect(id: String) = room.websocket[Receiver, JsValue](id)

  // or
  def connect(id: String) = room.websocket[JsValue](id, Props[Receiver])

}
```

## All together

```scala
import akka.actor._

import play.api._
import play.api.mvc._
import play.api.libs.json._

// Implicits
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._

import org.mandubian.actorroom._

class Receiver extends Actor {
  def receive = {
    case Received(from, js: JsValue) =>
      (js \ "msg").asOpt[String] match {
        case None => play.Logger.error("couldn't msg in websocket event")
        case Some(s) =>
          play.Logger.info(s"received $s")
          context.parent ! Broadcast(from, Json.obj("msg" -> s))
      }
  }
}

object Application extends Controller {

  val room = Room()

  def websocket(id: String) = room.websocket[Receiver, JsValue](id)

}

```

# Extend default behaviors

## Override the administration message format

`AdminMsgFormatter` typeclass is used by ActorRoom to format administration messages (Connected, Disconnected and Error) by default.

`AdminMsgFormatter[JsValue]` and `AdminMsgFormatter[String]` are provided by default.

You can override the format as following:

```scala

// put this implicit in the same scope where you create your websocket endpoint
implicit val msgFormatter = new AdminMsgFormatter[JsValue]{
    def connected(id: String) = Json.obj("kind" -> "connected", "id" -> id)
    def disconnected(id: String) = Json.obj("kind" -> "disconnected", "id" -> id)
    def error(id: String, msg: String) = Json.obj("kind" -> "error", "id" -> id, "msg" -> msg)
}

// then this msgFormatter will be used for all administration messages  
def websocket(id: String) = room.websocket[Receiver, JsValue](id)

```

## Override the Sender Actor

You just have to create a new actor as following:

```scala
class MyCustomSender extends Actor {

  def receive = {
    case s: Send[JsValue]        => // message send from a member to another one

    case b: Broadcast[JsValue]   => // message broadcast by a member

    case Connected(id)           => // member "id" has connected

    case Disconnected(id)        => // member "id" has disconnected

    case Init(id, receiverActor) => // Message sent when sender actor is initialized by ActorRoom

  }

}
```

Then you must initialize your websocket with it

```scala
  def connect(id: String) = room.websocket[JsValue](id, Props[Receiver], Props[MyCustomSender])
```

You can override the following messages:

```scala
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
```


## Override the Supervisor Actor

Please note `Supervisor` is an actor which manages a internal state containing all members:

```scala
  var members = Map.empty[String, Member]
```

You can override the default Supervisor as following:

```scala
  class CustomSupervisor extends Supervisor {

    def customBroadcast: Receive = {
      case Broadcast(from, js: JsObject) =>
        // adds members to all messages
        val ids = Json.obj("members" -> members.map(_._1))

        members.foreach {
          case (id, member) =>
            member.sender ! Broadcast(from, js ++ ids)

          case _ => ()
        }
    }

    override def receive = customBroadcast orElse super.receive
  }
```

## Create a bot to simulate member

A bot is a fake member that you can use to communicate with other members. It's identified by an ID as any member.

You create a bot with these API:

```scala
case class Member(id: String, val receiver: ActorRef, val sender: ActorRef) extends Message

def bot[Payload](id: String)
    (implicit msgFormatter: AdminMsgFormatter[Payload]): Future[Member]

def bot[Payload](
    id: String,
    senderProps: Props
  )(implicit msgFormatter: AdminMsgFormatter[Payload]): Future[Member]


def bot[Payload](
    id: String,
    receiverProps: Props,
    senderProps: Props): Future[Member]

```

Then with returned `Member`, you can simulate messages:

```scala
val room = Room()

val bot = room.bot[JsValue]("robot")

// simulate a received message
bot.receiver ! Received(bod.id, Json.obj("foo" -> "bar"))
```

Naturally, you can override the Bot Sender Actor

```scala
/** The default actor sender for Bots */
class BotSender extends Actor {

  def receive = {
    case s =>
      play.Logger.info(s"Bot should have sent ${s}")

  }

}

val bot = room.bot[JsValue]("robot", Props[BotSender])

```

So what else???
Everything you can override and everything that I didn't implement yet...

Have fun!
