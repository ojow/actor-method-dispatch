package ojow.actor

import akka.actor._


class LinkedTo[T <: Actor](a: T) extends ActorMethods {

  type ActorState = T

  override protected def actor = a

  override protected def self = actor.self

}


