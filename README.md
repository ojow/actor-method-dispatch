### actor-method-dispatch

The project introduces a type-safe layer on top of the current Akka Actor API.

### Features

  - Type safety in both directions expressed with method calls: set of accepted messages and their return types. Methods can be of two kinds: a) named `tell<something>` and returning `Unit`, b) named `ask<something>` and returning a `Reply` which can be either converted to a `Future` or used to assign a handler within an Actor (no closures stored/passed, everything is serializable!) or used to stop the actor from replying at all.
  - Autocompletion and "go to implementation" in IDE's.
  - Reduced boilerplate: no need for case classes for messages (and hence no need to repeat message names and parameters in `receive`), no need for extra methods when you need to call one message handler from another.
  - You still have control over all Actor features: raw messages, become, supervision etc.
  - No JDK proxies, no bytecode hacks, pure Scala macros.

#### Counter actor with a `var`:
```scala
class CounterActor extends Actor {
  var i: Int = 0
  override def receive = swappableMethods(new LinkedTo(this) with CounterActorInterface)
}

trait CounterActorInterface extends ActorMethodsOf[CounterActor] {
  def askIncrementAndGet(): Reply[Int] = {
    actor.i += 1
    Reply(actor.i)
  }

  def tellReset(): Unit = {
    actor.i = 0
  }
}
```

#### Counter actor wihout `var`s, using `become`:
```scala
class CounterActor extends Actor {
  override def receive = behavior(0)

  def behavior(i: Int): Receive = swappableMethods(new LinkedTo(this) with CounterActorInterface {
    def askIncrementAndGet(): Reply[Int] = {
      context.become(behavior(i + 1))
      Reply(i + 1)
    }
  })

}

trait CounterActorInterface extends ActorMethodsOf[CounterActor] {
  def askIncrementAndGet(): Reply[Int]
  def tellReset(): Unit = actor.context.become(actor.behavior(0))
}
```

#### Calling methods/sending messages:
```scala
// Create a proxy which is then used to send messages via method calls
val myActor = actorMethodsProxy[CounterActorInterface](sys.actorOf(Props[CounterActor]))

// Calling a 'tell' method, return type Unit
myActor.tellReset()

// Calling an 'ask' method, returns a Reply which can be converted to a Future
val result: Future[Int] = myActor.askIncrementAndGet().toFuture

// The reply message will not be sent at all
myActor.askIncrementAndGet().ignoreReply()
```

#### Handling replies with an Actor:
```scala
// State inside the Actor:
var replyAddress: Option[ReplyAddress[String]] = None

// Methods inside the public interface trait:
def askCollectData(implicit replyAddress: ReplyAddress[String]): Reply[String] = {
  // save reply address for later use
  actor.replyAddress = Some(replyAddress)

  // Send a message along with meta info on how to handle the reply
  provider.askIntData.handleWith(replyHandler(tellIntDataReply(someContext)))

  WillReplyLater
}

// Here we handle replies from provider.askIntData
def tellIntDataReply(context: SomeContext)(intData: Int): Unit = {
  // Replying to the saved address when we are ready
  actor.replyAddress.map(_.sendReply(s"Data collected: $intData"))
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



