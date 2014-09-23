package ojow.actor

import akka.actor.{ActorRef, Actor}


trait ActorMethods {
  
  type ActorState <: Actor

  protected def thisActor: ActorState

  protected implicit def self: ActorRef

}

trait ActorMethodsOf[T <: Actor] extends ActorMethods {

  override type ActorState = T

}


abstract class ActorRefWithMethods(val actorRef: ActorRef) extends ActorMethods with Serializable


