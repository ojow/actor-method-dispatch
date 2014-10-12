package ojow.actor

import akka.actor.{Actor, Status, ActorRef}
import akka.pattern.ask
import akka.util.Timeout
import scala.annotation.unchecked.uncheckedVariance
import scala.language.existentials
import scala.language.higherKinds

import scala.concurrent.Future


trait WrappedActorRef {

  def ref: ActorRef

}


trait Receiver[-I] extends WrappedActorRef {

  def send(value: I)(implicit sender: ActorRef = Actor.noSender): Unit

}


object NoReply extends Receiver[Any] with Serializable {

  override def ref: ActorRef = throw new RuntimeException("NoReply.ref call")

  override def send(value: Any)(implicit sender: ActorRef) = {}

}


object ReplyToSender extends Receiver[Any] with Serializable {

  override def ref: ActorRef = throw new RuntimeException("ReplyToSender.ref call")

  override def send(value: Any)(implicit sender: ActorRef) = throw new RuntimeException("ReplyToSender.send call")

}


trait ActorMethod {

  def name: String

  def pars: ActorMethod.ParamsCollection[Any]

  def exh: Option[Receiver[Status.Failure]]

  def withExceptionHandler(exh: Receiver[Status.Failure]): ActorMethod

  protected[actor] def handler: Option[Receiver[_]]

  protected[actor] def sendReply(ref: ActorRef, value: Any)(implicit sender: ActorRef = Actor.noSender): Unit =
    handler.foreach(fixReplyRef(ref, _).asInstanceOf[Receiver[Any]].send(value))

  protected[actor] def sendException(ref: ActorRef, exception: Status.Failure)(implicit sender: ActorRef = Actor.noSender): Unit =
    exh.foreach(fixReplyRef(ref, _).send(exception))

  protected[actor] def fixReplyRef[T](ref: ActorRef, h: Receiver[T]): Receiver[T] = h match {
    case ReplyToSender => AR(ref)
    case x => x
  }

  // Name based exctraction methods

  def isEmpty: Boolean = false

  def get: ActorMethod = this

  def _1: String = name

  def _2: ActorMethod.ParamsCollection[Any] = pars

  def _3: Option[Receiver[_]] = handler

  def _4: Option[Receiver[Status.Failure]] = exh

}


object ActorMethod {

  type ParamsCollection[T] = List[T]

  def ParamsCollection[T](args: T*): ParamsCollection[T] = List[T](args: _*)

  def unapply(m: ActorMethod) = m

}


trait SendNoReply extends ActorMethod {

  protected[actor] def handler: Option[Receiver[_]] = None

}


trait Sender[+O] extends WrappedActorRef with ActorMethod with SendNoReply {

  def withReplyHandler(hnd: Receiver[O]): SenderWithHandler

  def ignoreReply(): SenderWithHandler

  def pipeTo(ctx: ActorMethodContext[O]): SenderWithHandler

  def runAsFuture()(implicit timeout: Timeout): Future[O] =
    (ref ? this.withReplyHandler(ReplyToSender).withExceptionHandler(ReplyToSender)).asInstanceOf[Future[O]]

}


trait SenderWithHandler extends ActorMethod {

  def hnd: Receiver[_]

  protected[actor] def handler: Option[Receiver[_]] = Some(hnd)

}


trait NoArgsActorMethod extends WrappedActorRef with ActorMethod {

  def asMessage: ActorMethod

  def run()(implicit sender: ActorRef = Actor.noSender): Unit = ref ! asMessage

}


trait CurriedActorMethod[-I] extends ActorMethod with Receiver[I] {

  type UncurryMore[T] <: CurriedActorMethod[T]

  type UncurryDone <: NoArgsActorMethod

  def uncurry[A, To](value: A)(implicit rule: UncurryRule[I @uncheckedVariance, A, To]):
                                                                               rule.Result[UncurryMore[To], UncurryDone]

  override def send(value: I)(implicit sender: ActorRef = Actor.noSender) = uncurry(value).run()

}


case class AMRSMsg(name: String, pars: ActorMethod.ParamsCollection[Any]) extends ActorMethod {

  override def handler: Option[Receiver[_]] = None

  override def exh: Option[Receiver[Status.Failure]] = None

  override def withExceptionHandler(exh: Receiver[Status.Failure]) = AMRMsg(name, pars, handler, Some(exh))

}


case class AMRMsg(name: String, pars: ActorMethod.ParamsCollection[Any], handler: Option[Receiver[_]] = None,
                  exh: Option[Receiver[Status.Failure]] = None) extends ActorMethod {

  override def withExceptionHandler(exh: Receiver[Status.Failure]) = copy(exh = Some(exh))

}


case class AR[-I](ref: ActorRef) extends Receiver[I] {

  override def send(value: I)(implicit sender: ActorRef) = ref ! value

}


case class AMR(ref: ActorRef, name: String, pars: ActorMethod.ParamsCollection[Any],
                              exh: Option[Receiver[Status.Failure]] = None) extends NoArgsActorMethod with SendNoReply {

  override def asMessage = AMRMsg(name, pars, None, exh)

  override def withExceptionHandler(exh: Receiver[Status.Failure]) = copy(exh = Some(exh))

}


case class AMRH(ref: ActorRef, name: String, pars: ActorMethod.ParamsCollection[Any],
                exh: Option[Receiver[Status.Failure]] = None, hnd: Receiver[_]) extends NoArgsActorMethod with
                                                                                                     SenderWithHandler {

  override def asMessage = AMRMsg(name, pars, Some(hnd), exh)

  override def withExceptionHandler(exh: Receiver[Status.Failure]) = copy(exh = Some(exh))

}


case class IAMR[-I](ref: ActorRef, name: String, pars: ActorMethod.ParamsCollection[Any],
                          exh: Option[Receiver[Status.Failure]] = None) extends CurriedActorMethod[I] with SendNoReply {

  type UncurryMore[T] = IAMR[T]

  type UncurryDone = AMR

  override def withExceptionHandler(exh: Receiver[Status.Failure]) = copy(exh = Some(exh))

  override def uncurry[A, To](value: A)(implicit rule: UncurryRule[I @uncheckedVariance, A, To]):
                                                                             rule.Result[UncurryMore[To], UncurryDone] =
    rule.builder(IAMR[To](ref, name, rule.addArg(pars,value), exh), AMR(ref, name, rule.addArg(pars,value), exh))

}


case class IAMRH[-I](ref: ActorRef, name: String, pars: ActorMethod.ParamsCollection[Any],
                     exh: Option[Receiver[Status.Failure]] = None, hnd: Receiver[_]) extends CurriedActorMethod[I] with
                                                                                                     SenderWithHandler {

  type UncurryMore[T] = IAMRH[T]

  type UncurryDone = AMRH

  override def withExceptionHandler(exh: Receiver[Status.Failure]) = copy(exh = Some(exh))

  override def uncurry[A, To](value: A)(implicit rule: UncurryRule[I @uncheckedVariance, A, To]):
                                                                             rule.Result[UncurryMore[To], UncurryDone] =
    rule.builder(IAMRH[To](ref, name, rule.addArg(pars,value), exh, hnd),
      AMRH(ref, name, rule.addArg(pars,value), exh, hnd))
}


case class OAMR[+O](ref: ActorRef, name: String, pars: ActorMethod.ParamsCollection[Any],
                                exh: Option[Receiver[Status.Failure]] = None) extends NoArgsActorMethod with Sender[O] {

  override def asMessage = AMRMsg(name, pars, None, exh)

  override def withReplyHandler(hnd: Receiver[O]): AMRH = AMRH(ref, name, pars, exh, hnd)

  override def ignoreReply(): AMRH = AMRH(ref, name, pars, exh, NoReply)

  override def pipeTo(ctx: ActorMethodContext[O]): AMRH = AMRH(ref, name, pars, exh,
    ctx.message.handler.map(ctx.message.fixReplyRef(ctx.sender, _)).getOrElse(NoReply))

  override def withExceptionHandler(exh: Receiver[Status.Failure]) = copy(exh = Some(exh))

}


case class IOAMR[-I, +O](ref: ActorRef, name: String, pars: ActorMethod.ParamsCollection[Any],
                            exh: Option[Receiver[Status.Failure]] = None) extends CurriedActorMethod[I] with Sender[O] {

  type UncurryMore[T] = IOAMR[T, O @uncheckedVariance]

  type UncurryDone = OAMR[O @uncheckedVariance]

  override def uncurry[A, To](value: A)(implicit rule: UncurryRule[I @uncheckedVariance, A, To]):
                                       rule.Result[UncurryMore[To] @uncheckedVariance, UncurryDone @uncheckedVariance] =
    rule.builder(IOAMR[To, O](ref, name, rule.addArg(pars,value), exh), OAMR[O](ref, name, rule.addArg(pars,value), exh))

  override def withReplyHandler(hnd: Receiver[O]): IAMRH[I] = IAMRH(ref, name, pars, exh, hnd)

  override def ignoreReply(): IAMRH[I] = IAMRH(ref, name, pars, exh, NoReply)

  override def pipeTo(ctx: ActorMethodContext[O]): IAMRH[I] =
    IAMRH(ref, name, pars, exh, ctx.message.handler.map(ctx.message.fixReplyRef(ctx.sender, _)).getOrElse(NoReply))

  override def withExceptionHandler(exh: Receiver[Status.Failure]) = copy(exh = Some(exh))

}


abstract class UncurryRule[From, A, To] {

  type Result[More <: CurriedActorMethod[To], Done <: NoArgsActorMethod] <: ActorMethod

  def builder[More <: CurriedActorMethod[To], Done <: NoArgsActorMethod](more: => More, done: => Done): Result[More, Done]

  def addArg(oldArgs: ActorMethod.ParamsCollection[Any], newValue: Any): ActorMethod.ParamsCollection[Any] = {
    oldArgs.lastOption match {
      case Some(t: IncompleteTuple) => oldArgs.init :+ convertLastArg(IncompleteTuple(t.elems :+ newValue))
      case _ => oldArgs :+ convertLastArg(IncompleteTuple(Seq(newValue)))
    }
  }

  protected def convertLastArg(arg: IncompleteTuple): Any

}


class UncurryRuleDone[From, A, To] extends UncurryRule[From, A, To] {

  type Result[More <: CurriedActorMethod[To], Done <: NoArgsActorMethod] = Done

  override def builder[More <: CurriedActorMethod[To], Done <: NoArgsActorMethod](more: => More, done: => Done): Done = done

  override protected def convertLastArg(arg: IncompleteTuple): Any = arg.unwrap

}


class UncurryRuleMore[From, A, To] extends UncurryRule[From, A, To] {

  type Result[More <: CurriedActorMethod[To], Done <: NoArgsActorMethod] = More

  override def builder[More <: CurriedActorMethod[To], Done <: NoArgsActorMethod](more: => More, done: => Done): More = more

  override protected def convertLastArg(arg: IncompleteTuple): Any = arg

}


object UncurryRule {

  implicit def t0[A]: UncurryRuleDone[A, A, Nothing] = new UncurryRuleDone

  implicit def t1[A, B]: UncurryRuleMore[(A, B), A, B] = new UncurryRuleMore

  implicit def t2[A, B, C]: UncurryRuleMore[(A, B, C), A, (B, C)] = new UncurryRuleMore

  implicit def t3[A, B, C, D]: UncurryRuleMore[(A, B, C, D), A, (B, C, D)] = new UncurryRuleMore

  implicit def t4[A, B, C, D, E]: UncurryRuleMore[(A, B, C, D, E), A, (B, C, D, E)] = new UncurryRuleMore

}


case class IncompleteTuple(elems: Seq[Any]) {

  def unwrap: Any = elems match {
    case Seq(x) => x
    case Seq(x1, x2) => (x1, x2)
    case Seq(x1, x2, x3) => (x1, x2, x3)
    case Seq(x1, x2, x3, x4) => (x1, x2, x3, x4)
    case Seq(x1, x2, x3, x4, x5) => (x1, x2, x3, x4, x5)
  }

}


