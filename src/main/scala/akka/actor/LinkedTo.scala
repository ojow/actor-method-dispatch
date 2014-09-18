package akka.actor

class LinkedTo[T <: Actor](a: T) extends ActorMethods {

  type ActorState = T

  override protected def thisActor = a

  override protected def self = thisActor.self

}


