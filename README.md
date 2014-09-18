### actor-method-dispatch

An attempt to add some type safety to Akka Actors.

### Features

  - Type safety in both directions expressed with method calls: set of accepted messages and their return types. Methods can be of two kinds: a) named `tell<something>` and returning `Unit`, b) named `ask<something>` and returning a `Reply` which can be either converted to a `Future` or (when used from an Actor) used to assign a handler (without closures!).
  - Free IDE support - autocomplete and go to implementation.
  - Reduced boilerplate: no need for case classes for messages (and hence no need to repeat message names and parameters in `receive`), no need for extra methods when you need to call one message handler from another.
  - You still have control over all Actor features: raw messages, become, supervision etc.
  - No JDK proxies, no bytecode hacks, pure Scala macros.

#### Methods:
```scala
trait SimpleActorInterface extends ActorMethods {
  override type ActorState = SimpleActor
  def tellIncrement(): Unit = { thisActor.i += 1 }
  def askCurrentValue: Reply[Int] = Reply(thisActor.i)
}
```

#### Inside an Actor:
```scala
// selfMethods returns a Receive that routes messages to this Actor method calls
override def receive = selfMethods[SimpleActorInterface] orElse {
  case m => println(m) // you can still handle messages as usual
}

// swappableMethods returns Receive that routes messages to its own instance of ActorMethods
// LinkedTo(this) is here just to reduce boilerplate so you don't have to override 'thisActor' manually
def modifiedBehavior(step: Int): Receive = swappableMethods(new LinkedTo(this) with SimpleActorInterface {
  override def tellIncrement(): Unit = { thisActor.i += step }
})
```

#### Usage:
```scala
val myActor = actorMethodsProxy[SimpleActorInterface](sys.actorOf(Props[SimpleActor]))
myActor.tellIncrement()
val result: Future[Int] = myActor.askCurrentValue.toFuture
```

#### Handling replies inside an Actor:
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
// Or from another actor:
actor.askCollectData.handleWith(replyHandler(tellAcceptStringData))
```

See [tests](https://github.com/ojow/actor-method-dispatch/blob/master/src/test/scala/akka/actor) for more examples.

