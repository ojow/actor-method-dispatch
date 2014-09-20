### actor-method-dispatch

An attempt to add some type safety to Akka Actors.

### Features

  - Type safety in both directions expressed with method calls: set of accepted messages and their return types. Methods can be of two kinds: a) named `tell<something>` and returning `Unit`, b) named `ask<something>` and returning a `Reply` which can be either converted to a `Future` or used to assign a handler within an Actor (no closures stored/passed, everything is serializable!) or used to stop the actor from replying at all.
  - Free IDE support - autocomplete and go to implementation.
  - Reduced boilerplate: no need for case classes for messages (and hence no need to repeat message names and parameters in `receive`), no need for extra methods when you need to call one message handler from another.
  - You still have control over all Actor features: raw messages, become, supervision etc.
  - No JDK proxies, no bytecode hacks, pure Scala macros.

#### Methods:
```scala
trait ActorInterface extends ActorMethods {
  override type ActorState = SimpleActor
  def tellIncrement(): Unit = { thisActor.i += 1 }
  def askCurrentValue = Reply(thisActor.i)
}
```

#### Inside an Actor:
```scala
// selfMethods returns a Receive that routes messages to this Actor method calls
override def receive = selfMethods[ActorInterface] orElse {
  case m => println(m) // you can still handle messages as usual
}

// swappableMethods returns a Receive that routes messages to its own copy of ActorMethods
def behavior(step: Int): Receive = swappableMethods(new LinkedTo(this) with ActorInterface {
  override def tellIncrement(): Unit = { thisActor.i += step }
})
```

#### Calling methods/sending messages:
```scala
val myActor = actorMethodsProxy[ActorInterface](sys.actorOf(Props[SimpleActor]))
myActor.tellIncrement()
val result: Future[Int] = myActor.askCurrentValue.toFuture
// The reply message will not be sent at all
myActor.askCurrentValue.ignoreReply()
```

#### Handling replies with an Actor:
```scala
var replyAddress: Option[ReplyAddress[String]] = None

def askCollectData(implicit replyAddress: ReplyAddress[String]): Reply[String] = {
  // save reply address for later use
  thisActor.replyAddress = Some(replyAddress)

  // Send a message along with meta info on how to handle the reply
  provider.askIntData.handleWith(replyHandler(tellIntDataReply(someContext)))

  WillReplyLater
}

// Here we handle replies from provider.askIntData
def tellIntDataReply(context: SomeContext)(intData: Int): Unit = {
  // Replying to the saved address when we are ready
  thisActor.replyAddress.map(_.sendReply(s"Data collected: $intData"))
}

// Using from outside is the same:
val result: Future[String] = actor.askCollectData.toFuture
// Or with another actor:
actor.askCollectData.handleWith(replyHandler(anotherActor.tellAcceptStringData))
```

See [tests](https://github.com/ojow/actor-method-dispatch/blob/master/src/test/scala/ojow/actor) for more examples.

#### How it works
There are 3 kind of macros:
  1. For creating proxies. They just override methods on your trait with code to send `ActorMethodCall` messages.
  2. For creating `Receive`s. They build a `Receive` from a list of `case` clauses to match `ActorMethodCall` messages and call respective methods.
  3. For creating `ReplyAddress`es (to handle replies) from curried method calls (without the last argument list).

See [macro sources](https://github.com/ojow/actor-method-dispatch/blob/master/macro/src/main/scala/ojow/actor) for details.

The artifacts are not getting published yet, so to use in another project clone or download this one, run `sbt publishLocal` and then add `"akka" % "actor-method-dispatch" % "0.2-SNAPSHOT"` dependency to the target project's build.


