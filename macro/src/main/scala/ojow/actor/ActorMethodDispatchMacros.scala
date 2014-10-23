package ojow.actor

import akka.util.Timeout

import language.experimental.macros
import scala.concurrent.ExecutionContext
import scala.reflect.macros.blackbox
import akka.actor._


object ActorMethodDispatchMacros {

  def askMethodPrefix = "ask"

  def tellMethodPrefix = "tell"


  /**
   * Return a set of { case ... => ... } clauses which is a Receive that matches ActorMethodCall messages and
   * calls corresponding methods on 'this'.
   */
  def selfMethods[T <: ActorMethods]: Actor.Receive = macro selfMethodsImpl[T]

  def selfMethodsImpl[T <: ActorMethods : c.WeakTypeTag](c: blackbox.Context): c.Expr[Actor.Receive] = {
    import c.universe._
    val tpe = weakTypeOf[T]

    c.Expr[Actor.Receive](methodsToCases(c)(tpe, q"this"))
  }


  /**
   * Works similar to 'selfMethods' except methods are called on the given object.
   */
  def swappableMethods[T <: ActorMethods](obj: => T): Actor.Receive =
    macro swappableMethodsImpl[T]

  def swappableMethodsImpl[T <: ActorMethods : c.WeakTypeTag](c: blackbox.Context)(obj: c.Tree): c.Expr[Actor.Receive] = {
    import c.universe._
    val tpe = weakTypeOf[T]

    c.Expr[Actor.Receive](q"""
      new PartialFunction[Any, Unit] {
        val methodsObj = $obj
        val recv: _root_.akka.actor.Actor.Receive = ${methodsToCases(c)(tpe, q"methodsObj")}
        override def isDefinedAt(x: Any) = recv.isDefinedAt(x)
        override def apply(v1: Any) = recv.apply(v1)
      }
     """)

  }


  /**
   * Returns an anonymous class instantion expression. The class is the given 'T' with methods (suitable for message
   * dispatching) overriden with code than makes it possible to send messages to the given ActorRef.
   */
  def actorMethodsProxy[T <: ActorMethods](ref: ActorRef): T = macro actorMethodsProxyImpl[T]

  def actorMethodsProxyImpl[T <: ActorMethods : c.WeakTypeTag](c: blackbox.Context)(ref: c.Expr[ActorRef]): c.Expr[T] = {
    import c.universe._
    val tpe = weakTypeOf[T]

    val (tellMethods, askReplyMethods, protectedAbstractMethods) = selectMethods(c)(tpe.members)

    def method2override(m: c.universe.MethodSymbol, body: (Tree, Tree) => Tree): Tree = {
      val (paramss, implicitParams) = paramLists(c)(m)
      val paramsDef = paramss.map(_.map(sym => q"${sym.name.toTermName}: ${sym.typeSignature}"))
      val implicitParamsDef = implicitParams.map(sym => q"${sym.name.toTermName}: ${sym.typeSignature}")
      val argValues = q"_root_.ojow.actor.ActorMethod.ParamsCollection(..${m.paramLists.flatten.map(sym => q"${sym.name.toTermName}")})"
      val name = Literal(Constant(m.name.decodedName.toString))

      q"override def ${m.name}(...$paramsDef)(implicit ..$implicitParamsDef) = ${body(q"$name", q"$argValues")}"
    }

    val protectedAbstractOverrides = protectedAbstractMethods.map(m => method2override(m, (_, _) => q"proxyError"))

    val tellOverrides = tellMethods.map(m => method2override(m, (name, argValues) =>
      q"""self ! _root_.ojow.actor.AMR(self, $name, $argValues)"""))

    val askReplyOverrides = askReplyMethods.map(m => method2override(m, (name, argValues) => {
      q""" new ${m.returnType} {
         override def value = proxyError
         override def actorRef = self
         override def methodName: String = $name
         override def params: _root_.ojow.actor.ActorMethod.ParamsCollection[Any] = $argValues
      } """}))

    c.Expr[T] {q"""
      new $tpe with _root_.ojow.actor.ActorMethodsProxy {
        override val self = $ref
        ..${tellOverrides ++ askReplyOverrides ++ protectedAbstractOverrides}
      }
    """
    }

  }


  def methodRefIO[I, O](method: I => Reply[O]): IOAMR[I, O] = macro methodRefImplIO[I, O]

  def methodRefImplIO[I, O](c: blackbox.Context)(method: c.Tree): c.Expr[IOAMR[I, O]] = {
    import c.universe._

    c.Expr[IOAMR[I, O]](currMref(c)(method, (ref, nameLiteral, args) => q"_root_.ojow.actor.IOAMR($ref, $nameLiteral, $args)"))
  }


  def methodRefI[I, T](method: I => T)(implicit ev: T =:= Unit): IAMR[I] = macro methodRefImplI[I]

  def methodRefImplI[I](c: blackbox.Context)(method: c.Tree)(ev: c.Tree): c.Expr[IAMR[I]] = {
    import c.universe._

    c.Expr[IAMR[I]](currMref(c)(method, (ref, nameLiteral, args) => q"_root_.ojow.actor.IAMR($ref, $nameLiteral, $args)"))
  }


  def methodRefO[O](method: => Reply[O]): OAMR[O] = macro methodRefImplO[O]

  def methodRefImplO[O](c: blackbox.Context)(method: c.Tree): c.Expr[OAMR[O]] = {
    import c.universe._

    c.Expr[OAMR[O]](mref(c)(method, (ref, nameLiteral, args) => q"_root_.ojow.actor.OAMR($ref, $nameLiteral, $args)"))
  }


  def methodRef[T](method: => T)(implicit ev: T =:= Unit): AMR = macro methodRefImpl

  def methodRefImpl(c: blackbox.Context)(method: c.Tree)(ev: c.Tree): c.Expr[AMR] = {
    import c.universe._

    c.Expr[AMR](mref(c)(method, (ref, nameLiteral, args) => q"_root_.ojow.actor.AMR($ref, $nameLiteral, $args)"))
  }


  private def mref(c: blackbox.Context)(method: c.Tree, result: (c.Tree, c.Tree, c.Tree) => c.Tree): c.Tree = {
    import c.universe._

    method match {
      case q"$selector.$name(...$argss)" =>
        val args = q"_root_.ojow.actor.ActorMethod.ParamsCollection(..${argss.flatten})"
        val nameLiteral = Literal(Constant(name.decodedName.toString))
        val ref = q"$selector.self"
        result(ref, nameLiteral, args)

      case _ => reportError(c, "The argument must be a method call.")
    }
  }


  private def currMref(c: blackbox.Context)(method: c.Tree, result: (c.Tree, c.Tree, c.Tree) => c.Tree): c.Tree = {
    import c.universe._

    def failed = reportError(c, "The argument must look like a method call without the last argument list.")

    method match {
      case q"{((${q"$mods val $tname: $tpt = $expr"}) => $selector.${mname: TermName}(...$argss))}" =>
        val argsToPass: List[c.Tree] = {
          def isFuncArg(tree: Option[List[c.Tree]]): Boolean = tree match {
            case Some(List(q"${tn: TermName}")) if tn == tname => true
            case _ => false
          }
          if (isFuncArg(argss.lastOption)) argss.init.flatten
          else if (isFuncArg(argss.init.lastOption)) argss.init.init.flatten
          else failed
        }

        val args = q"_root_.ojow.actor.ActorMethod.ParamsCollection(..$argsToPass)"
        val nameLiteral = Literal(Constant(mname.decodedName.toString))
        val ref = q"$selector.self"
        result(ref, nameLiteral, args)

      case _ => failed
    }
  }


  private[actor] def reportError(c: blackbox.Context, msg: String): Nothing = c.abort(c.enclosingPosition, msg)

  /**
   * Partitions regular and implicit parameters of a method
   */
  private def paramLists(c: blackbox.Context)(method: c.universe.MethodSymbol): (List[List[c.universe.Symbol]], List[c.universe.Symbol]) = {
    val (params, implicitParamLists) = method.paramLists.partition(_.headOption.exists(! _.isImplicit))
    val implicitParams = implicitParamLists.headOption.getOrElse(Nil)
    (params, implicitParams)
  }

  /**
   * Selects and validates methods suitable for message dispatching
   */
  private def selectMethods(c: blackbox.Context)(members: c.universe.MemberScope):
        (Iterable[c.universe.MethodSymbol], Iterable[c.universe.MethodSymbol], Iterable[c.universe.MethodSymbol]) = {
    import c.universe._
    def nameFilter(s: String): Boolean = s.startsWith(askMethodPrefix) || s.startsWith(tellMethodPrefix)

    val actorMembers = typeOf[Actor].members.map(_.name.decodedName.toString).toSet
    val ms = members.filterNot(m => actorMembers.contains(m.name.decodedName.toString))

    val duplicates = ms.groupBy(m => m.name.decodedName.toString).collect { case (x, ys) if ys.size > 1 => x }
    if (duplicates.nonEmpty) reportError(c, s"Overloading is not supported. Overloaded methods: ${duplicates.mkString(", ")}")

    val reservedMethodNames = Set("self", "actor")
    val protectedAbstractMethods = ms.collect {
      case x if x.isMethod && x.isProtected && x.isAbstract &&
        !reservedMethodNames.contains(x.name.decodedName.toString) => x.asMethod
    }

    val publicMethods = ms.collect {
      case x if x.isMethod && x.isPublic =>
        if (nameFilter(x.name.decodedName.toString)) x.asMethod
        else reportError(c,
          s"Illegal method: '${x.name}'. All public methods must either start with '$tellMethodPrefix' or '$askMethodPrefix'.")
    }
    val (tellMethods, askMethods) = publicMethods.partition(_.name.decodedName.toString.startsWith(tellMethodPrefix))

    tellMethods.foreach { m =>
      if (m.returnType != typeOf[Unit])
        reportError(c, s"Method '${m.name}' must have return type Unit.")
    }

    askMethods.foreach { m =>
      if (!(m.returnType <:< typeOf[Reply[_]]))
        reportError(c, s"Method '${m.name}' must return a Reply.")
    }

    (tellMethods, askMethods, protectedAbstractMethods)
  }

  /**
   * Converts suitable for message dispatching methods of type 'tpe' to a set of { case ... => ... } clauses
   * which is a Receive that matches ActorMethodCall messages and calls corresponding methods
   */
  private def methodsToCases(c: blackbox.Context)(tpe: c.universe.Type, selector: c.Tree): c.universe.Tree = {
    import c.universe._
    val (tellMethods, askReplyMethods, _) = selectMethods(c)(tpe.members)

    def methodToCase(m: MethodSymbol, methodCallHandler: (Option[c.Tree] => c.Tree, List[Symbol]) => c.Tree): Tree = {
      val (params, implicitParams) = paramLists(c)(m)
      val paramListLengths = params.map(_.length).toIndexedSeq
      val methodParams = params.zipWithIndex.map {
        case (xs, i) => xs.zipWithIndex.map {
          case (param, j) => q"args(${paramListLengths.take(i).sum} + $j).asInstanceOf[${param.typeSignature}]"
        }
      }

      val methodNamePattern = pq"${m.name.decodedName.toString}"
      val handler = methodCallHandler(impls => q"$selector.${m.name}(...${impls.map(i => methodParams :+ List(i)).getOrElse(methodParams)})", implicitParams)
      cq"""msg@_root_.ojow.actor.ActorMethod($methodNamePattern, args, _, _) => $handler"""
    }

    val tellCases = tellMethods.map(methodToCase(_, (call, _) => call(None)))

    val askReplyCases = askReplyMethods.map(methodToCase(_, (call, implParams) => {
      val amCtx =  if (implParams.nonEmpty) Some(q"amCtx") else None // TODO: correct check

      q"""
        val amCtx = _root_.ojow.actor.ActorMethodContext(sender(), msg)
        try {
          val result = ${call(amCtx)}
          if (result != _root_.ojow.actor.WillReplyLater) {
            amCtx.sendReply(result.value)
          }
        }
        catch {
          case e: _root_.java.lang.Exception =>
            amCtx.sendException(_root_.akka.actor.Status.Failure(e))
            throw e
        }
      """}))

    q"{ case ..${tellCases ++ askReplyCases} }"
  }
}


