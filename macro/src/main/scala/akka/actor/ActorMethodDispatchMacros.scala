package akka.actor

import akka.util.Timeout

import language.experimental.macros
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.macros.blackbox


object ActorMethodDispatchMacros {

  def askMethodPrefix = "ask"

  def tellMethodPrefix = "tell"


  def selfMethods[T <: ActorMethods](implicit ec: ExecutionContext): Actor.Receive = macro selfMethodsImpl[T]

  def selfMethodsImpl[T <: ActorMethods : c.WeakTypeTag](c: blackbox.Context)(
                                                              ec: c.Expr[ExecutionContext]): c.Expr[Actor.Receive] = {
    import c.universe._
    val tpe = weakTypeOf[T]

    c.Expr[Actor.Receive](methods2cases(c)(tpe, q"this", ec))
  }


  def swappableMethods[T <: ActorMethods](obj: => T)(implicit ec: ExecutionContext): Actor.Receive =
    macro swappableMethodsImpl[T]

  def swappableMethodsImpl[T <: ActorMethods : c.WeakTypeTag](c: blackbox.Context)(obj: c.Tree)(
                                                              ec: c.Expr[ExecutionContext]): c.Expr[Actor.Receive] = {
    import c.universe._
    val tpe = weakTypeOf[T]

    c.Expr[Actor.Receive](q"""
      new PartialFunction[Any, Unit] {
        val methodsObj = $obj
        val recv: Receive = ${methods2cases(c)(tpe, q"methodsObj", ec)}
        override def isDefinedAt(x: Any) = recv.isDefinedAt(x)
        override def apply(v1: Any) = recv.apply(v1)
      }
     """)

  }


  def actorMethodsProxy[T <: ActorMethods](ref: ActorRef)(implicit askTimeout: Timeout,
                                                          ec: ExecutionContext): T = macro actorMethodsProxyImpl[T]

  def actorMethodsProxyImpl[T <: ActorMethods : c.WeakTypeTag](c: blackbox.Context)(ref: c.Expr[ActorRef])(
                                            askTimeout: c.Expr[Timeout], ec: c.Expr[ExecutionContext]): c.Expr[T] = {
    import c.universe._
    val tpe = weakTypeOf[T]

    val (tellMethods, askMethods) = selectMethods(c)(tpe.members)

    def method2override(m: c.universe.MethodSymbol, body: (Tree, Tree, Tree) => Tree): Tree = {
      val argsDef = m.paramLists.map(_.map(sym => q"${sym.name.toTermName}: ${sym.typeSignature}"))
      val argValues = q"List(..${m.paramLists.map(xs => q"List(..${xs.map(sym => q"${sym.name.toTermName}")})")})"
      val name = Literal(Constant(m.name.decodedName.toString))

      q"override def ${m.name}(...$argsDef) = ${body(q"$name", q"$argValues", q"${m.returnType}")}"
    }

    val tellOverrides = tellMethods.map(method2override(_, { case (name, argValues, _) =>
      q"actorRef ! akka.actor.ActorMethodCall($name, $argValues)"}))
    val askOverrides = askMethods.map(method2override(_, { case (name, argValues, typ) =>
      q"akka.pattern.ask(actorRef, akka.actor.ActorMethodCall($name, $argValues))($askTimeout).asInstanceOf[$typ]"}))

    c.Expr[T] {q"""
      new akka.actor.ActorRefWithMethods($ref) with $tpe {
        override protected def thisActor = throw new RuntimeException("This method must not be called on a proxy.")
        ..${tellOverrides ++ askOverrides}
      }
    """
    }

  }


  private def reportError(c: blackbox.Context, msg: String): Nothing = c.abort(c.enclosingPosition, msg)

  private def selectMethods(c: blackbox.Context)(members: c.universe.MemberScope): (Iterable[c.universe.MethodSymbol],
                                                                                    Iterable[c.universe.MethodSymbol]) = {
    import c.universe._
    def nameFilter(s: String): Boolean = s.startsWith(askMethodPrefix) || s.startsWith(tellMethodPrefix)

    val actorMembers = typeOf[Actor].members.map(_.name.decodedName.toString).toSet
    val ms = members.filterNot(m => actorMembers.contains(m.name.decodedName.toString))

    val duplicates = ms.groupBy(m => m.name.decodedName.toString).collect { case (x, ys) if ys.size > 1 => x }
    if (duplicates.nonEmpty) reportError(c, s"Overloading is not supported. Overloaded methods: ${duplicates.mkString(", ")}")

    val methods = ms.collect {
      case x if x.isMethod && x.isPublic =>
        if (nameFilter(x.name.decodedName.toString)) x.asMethod
        else reportError(c,
          s"Illegal method: '${x.name}'. All public methods must either start with '$tellMethodPrefix' or '$askMethodPrefix'.")
    }
    val (tellMethods, askMethods) = methods.partition(_.name.decodedName.toString.startsWith(tellMethodPrefix))

    tellMethods.foreach { m =>
      if (m.returnType != typeOf[Unit])
        reportError(c, s"Method '${m.name}' must have return type Unit.")
    }

    askMethods.foreach { m =>
      if (!(m.returnType <:< typeOf[Future[_]]))
        reportError(c, s"Method '${m.name}' must return a Future.")
    }

    (tellMethods, askMethods)
  }

  private def methods2cases(c: blackbox.Context)(tpe: c.universe.Type, selector: c.Tree,
                                                 ec: c.Expr[ExecutionContext]): c.universe.Tree = {
    import c.universe._
    val (tellMethods, askMethods) = selectMethods(c)(tpe.members)

    def methods2case(m: c.universe.MethodSymbol, methodCallHandler: c.Tree => c.Tree): Tree = {
      val methodParams = m.paramLists.zipWithIndex.map {
        case (xs, i) => xs.zipWithIndex.map {
          case (param, j) => q"args($i)($j).asInstanceOf[${param.typeSignature}]"
        }
      }

      val methodNamePattern = pq"${m.name.decodedName.toString}"
      cq"""akka.actor.ActorMethodCall($methodNamePattern, args) =>
       ${methodCallHandler(q"$selector.${m.name}(...$methodParams)")}
      """
    }

    val tellCases = tellMethods.map(methods2case(_, call => call))
    val askCases = askMethods.map(methods2case(_, call =>
      q"""
        val sdr = sender()
        try {
          val result = $call
          result.foreach(sdr ! _)($ec)
        }
        catch {
          case e: Exception =>
            sdr ! akka.actor.Status.Failure(e)
            throw e
        }
      """))

    q"{ case ..${tellCases ++ askCases} }"
  }
}


