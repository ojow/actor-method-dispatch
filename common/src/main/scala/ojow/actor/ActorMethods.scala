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


class MethodsActor(initialReceive: Actor.Receive) extends Actor {

  override def receive: Receive = initialReceive

}


class SelfMethodsActor(initialReceive: Actor.Receive) extends MethodsActor(initialReceive) {
  this: ActorMethods =>

  override protected def thisActor = this

}


abstract class ActorRefWithMethods(val actorRef: ActorRef) extends ActorMethods with Serializable


