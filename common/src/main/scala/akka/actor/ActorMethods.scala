package akka.actor


trait ActorMethods {
  
  type ActorState <: Actor

  protected def thisActor: ActorState
}


abstract class ActorRefWithMethods(val actorRef: ActorRef) extends ActorMethods


case class ActorMethodCall(methodName: String, args: List[List[Any]])


