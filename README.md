### actor-method-dispatch

An attempt to add some type safety to Akka Actors.

### Features

  - Type safety in both directions expressed with method calls: set of accepted messages and their return types. Methods can be of two kinds: a) named `tell<something>` and returning `Unit`, b) named `ask<something>` and returning a `Future`.
  - Free IDE support - autocomplete and go to implementation.
  - Reduced boilerplate: no need for case classes for messages (and hence no need to repeat message names and parameters in `receive`), no need for extra methods when you need to call one message handler from another.
  - You still have control over all Actor features: raw messages, become, supervision etc.
  - No JDK proxies, no bytecode hacks, pure Scala macros.

#### Methods:
```scala
trait SimpleActorInterface extends ActorMethods {
  override type ActorState = SimpleActor
  def tellIncrement(): Unit = { thisActor.i += 1 }
  def askCurrentValue: Future[Int] = Future.successful(thisActor.i)
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
val result: Future[Int] = myActor.askCurrentValue
```

See [BasicActorMethodDispatchTest.scala](https://github.com/ojow/actor-method-dispatch/blob/master/src/test/scala/akka/actor/BasicActorMethodDispatchTest.scala) for more examples.

