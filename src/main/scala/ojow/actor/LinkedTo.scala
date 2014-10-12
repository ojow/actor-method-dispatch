package ojow.actor

import akka.actor._


class LinkedTo[T <: Actor](a: T) extends ActorMethods {

  type ActorState = T

  override def self = actor.self

  override protected def actor = a

}


trait ActorWithMethods[T <: Actor] extends Actor with ActorMethods {
  this: T =>

  override type ActorState = T

  override protected def actor = this
}
