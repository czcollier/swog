package scala.scalanative.native

import de.surfice.smacrotools.{BlackboxMacroTools, MacroAnnotationHandler}

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.reflect.macros.{TypecheckException, blackbox, whitebox}
import scala.language.experimental.macros
import scala.reflect.ClassTag

@compileTimeOnly("enable macro paradise to expand macro annotations")
class CObj(prefix: String = null, newSuffix: String = null) extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro CObj.Macro.impl
}

object CObj {

  trait CObjWrapper {
    def __ref: Any
  }

  trait CRef[T] {
    def __ref: Ref[T]
  }

  sealed trait Ref[+T]

  final class Out[T] {
    @inline def ptr: Ptr[Ptr[Byte]] = this.cast[Ptr[Ptr[Byte]]]
    @inline def isDefined: Boolean = !ptr != null
    @inline def isEmpty: Boolean = !isDefined
    @inline def valuePtr: Ptr[Byte] = !ptr
    @inline def value: Option[T] = macro Out.Macros.valueImpl[T]
    @inline def clear(): Unit = !ptr = null
  }
  object Out {

    def alloc[T](implicit zone: Zone): Out[T] = macro Macros.allocImpl[T]

    class Macros(val c: blackbox.Context) extends BlackboxMacroTools {
      import c.universe._

      val tAnyVal = weakTypeOf[AnyVal]

      def allocImpl[T: WeakTypeTag](zone: Tree) = {
        val tpe = weakTypeOf[T]
        val tree =
          q"""
              val ptr = scalanative.native.alloc[Ptr[Byte]]
              !ptr = null
              ptr.cast[scalanative.native.CObj.Out[$tpe]]
           """
        tree
      }

      def valueImpl[T: WeakTypeTag] = {
        val tpe = weakTypeOf[T]
        val self = c.prefix
        val tree =
          if(tpe <:< tAnyVal)
            q"""
               Some($self.valuePtr.cast[$tpe])
             """
          else
            q"""
             if($self.isDefined) Some(new $tpe($self.valuePtr.cast[scalanative.native.CObj.Ref[Nothing]]))
             else None
           """
//        println(tree)
        tree
      }
    }
  }


  object implicits {
    implicit class RichRef[T](val ref: Ref[T]) extends AnyVal {
      @inline def toPtr: Ptr[T] = ref.cast[Ptr[T]]
    }
  }

  trait CRefVoid extends CRef[Byte]

  class CRefWrapper extends StaticAnnotation


  private[native] class Macro(val c: whitebox.Context) extends MacroAnnotationHandler {
    import c.universe._

    override def annotationName = "CObj"
    override def supportsClasses: Boolean = true
    override def supportsTraits: Boolean = true
    override def supportsObjects: Boolean = true
    override def createCompanion: Boolean = true

    private val tPtrByte = weakTypeOf[Ptr[Byte]]
    private val tByte = weakTypeOf[Byte]
    private val tCRef = weakTypeOf[CRef[_]]
    private val tCRefVoid = weakTypeOf[CRefVoid]
    private val tAnyRef = weakTypeOf[AnyRef]
    private val selfPtr = q"val self: $tPtrByte"
    private val refPtr = q"__ref: $tPtrByte"
    private val expExtern = q"scalanative.native.extern"
    private val tCrefWrapperAnnotation = weakTypeOf[CRefWrapper]
    private val crefWrapperAnnotation = q"new scalanative.native.CObj.CRefWrapper"
    private val tCObjWrapper = weakTypeOf[CObjWrapper]

    private val annotationParamNames = Seq("prefix","newSuffix","semantics")

    implicit class MacroData(data: Map[String,Any]) {
      type Data = Map[String,Any]
      type Externals = Map[String,(String,Tree)]
      def externalPrefix: String = data.getOrElse("externalPrefix","").asInstanceOf[String]
      def externals: Externals = data.getOrElse("externals", Map()).asInstanceOf[Externals]
      def constructors: Seq[(String,Seq[ValDef])] = data.getOrElse("constructors",Nil).asInstanceOf[Seq[(String,Seq[ValDef])]]
      def newSuffix: String = data.getOrElse("newSuffix","").asInstanceOf[String]
      def crefType: Type = data.getOrElse("crefType",tCRefVoid).asInstanceOf[Type]
      def isAbstract: Boolean = data.getOrElse("isAbstract",false).asInstanceOf[Boolean]
      def parentIsCRef: Boolean = data.getOrElse("parentIsCRef",false).asInstanceOf[Boolean]
      def parentIsAbstract: Boolean = data.getOrElse("parentIsAbstract",false).asInstanceOf[Boolean]
      def fullName: String = data.getOrElse("fullName",false).asInstanceOf[String]
      def withExternalPrefix(prefix: String): Data = data.updated("externalPrefix",prefix)
      def withExternals(externals: Externals): Data = data.updated("externals",externals)
      def withConstructors(ctors: Seq[(String,Seq[Tree])]): Data = data.updated("constructors",ctors)
      def withNewSuffix(suffix: String): Data = data.updated("newSuffix",suffix)
      def withIsAbstract(flag: Boolean): Data = data.updated("isAbstract",flag)
      def withCRefType(tpe: Type): Data = data.updated("crefType",tpe)
      def withParentIsCRef(flag: Boolean): Data = data.updated("parentIsCRef",flag)
      def withParentIsAbstract(flag: Boolean): Data = data.updated("parentIsAbstract",flag)
      def withFullName(name: String): Data = data.updated("fullName",name)
    }

    override def analyze: Analysis = super.analyze andThen {
      case (cls: ClassParts, data) =>
        val updData = (
          analyzeMainAnnotation(cls) _
            andThen analyzeTypes(cls) _
            andThen analyzeConstructor(cls) _
            andThen analyzeBody(cls) _
          )(data)
        (cls, updData)
      case (trt: TraitParts, data) =>
        val updData = (
          analyzeMainAnnotation(trt) _
            andThen analyzeTypes(trt) _
            andThen analyzeBody(trt) _
          )(data)
        (trt, updData)
      case (obj: ObjectParts, data) =>
        val updData = (
          analyzeMainAnnotation(obj) _
          andThen analyzeBody(obj) _
        )(data)
        (obj, updData)
      case default => default
    }

    override def transform: Transformation = super.transform andThen {
      /* transform class */
      case cls: ClassTransformData =>
        val transformedBody = genTransformedTypeBody(cls)
        cls
          .updBody(transformedBody)
          .addAnnotations(crefWrapperAnnotation)
          .updCtorParams(genTransformedCtorParams(cls))
          .updParents(genTransformedParents(cls))
//          .updCtorMods(if(isAbstract(cls)) Modifiers() else Modifiers(Flag.PRIVATE)) // ensure that the concrete classes can't be instantiated using new
      /* transform trait */
      case trt: TraitTransformData =>
        val transformedBody = q"def __ref: scalanative.native.CObj.Ref[${trt.data.crefType}]" +: genTransformedTypeBody(trt)
        trt
          .updBody(transformedBody)
          .addAnnotations(crefWrapperAnnotation)
      /* transform companion object */
      case obj: ObjectTransformData =>
        val transformedBody = genTransformedCompanionBody(obj) :+ genBindingsObject(obj.data)
        obj.updBody(transformedBody)
      case default => default
    }


    private def analyzeMainAnnotation(tpe: CommonParts)(data: Data): Data = {
      val annotParams = extractAnnotationParameters(c.prefix.tree, annotationParamNames)
      val externalPrefix = annotParams("prefix") match {
        case Some(prefix) => extractStringConstant(prefix).get
        case None => genPrefixName(tpe.nameString)
      }
      val newSuffix = annotParams("newSuffix") match {
        case Some(suffix) => extractStringConstant(suffix).get
        case None => "new"
      }
      data.withExternalPrefix(externalPrefix).withNewSuffix(newSuffix)
    }

    // TODO: move all checks (isCRef, isAbstract, ... in here and store the results in data)
    private def analyzeTypes(tpe: TypeParts)(data: Data): Data = {
      val isAbstract = tpe.modifiers.hasFlag(Flag.ABSTRACT)
      val crefType = tpe.parents.map(getType(_)).filter( _ <:< tCRef ) match {
        case Nil => tByte
        case List(tpe@TypeRef(_,sym,args)) if sym.toString == "trait CRef" =>
          args.head
        case List(tpe) =>
          c.error(c.enclosingPosition,"CObj types can only directly extend CObj.CRef[T]")
          ???
        case types =>
          c.error(c.enclosingPosition,s"CObj types can't extend more than one instance of CObj.CRef (found: $types)")
          ???
      }
      val parentIsCRef = isCRefWrapper(tpe.parents.head)
      val parentIsAbstract = getType(tpe.parents.head).typeSymbol.isAbstract
      data
        .withIsAbstract(isAbstract)
        .withCRefType(crefType)
        .withParentIsCRef(parentIsCRef)
        .withParentIsAbstract(parentIsAbstract)
        .withFullName(tpe.fullName)
    }


    private def analyzeConstructor(cls: ClassParts)(data: Data): Data = {
      data.withConstructors( Seq( (genExternalName(data.externalPrefix,data.newSuffix),cls.params.asInstanceOf[List[ValDef]]) ) )
    }

    private def analyzeBody(tpe: CommonParts)(data: Data): Data = {
      val prefix = data.externalPrefix
      val typeExternals = tpe.body.collect {
        case t@DefDef(mods, name, types, args, rettype, rhs) if isExtern(rhs) =>
          genExternalBinding(prefix,t,!tpe.isObject,data)
      }
      val companionExternals = tpe match {
        case t: TypeParts => t.companion.map(_.body.collect {
          case t@DefDef(mods, name, types, args, rettype, rhs) if isExtern(rhs) =>
            genExternalBinding(prefix,t,false,data)
        }).getOrElse(Map())
        case _ => Nil
      }
      data.withExternals( (typeExternals ++ companionExternals).toMap )
    }

    private def genTransformedParents(cls: ClassTransformData): Seq[Tree] =
      if(!cls.data.isAbstract && cls.data.parentIsCRef && !cls.data.parentIsAbstract) {
        (cls.modParts.parents.head match {
          case x => q"$x(__ref)"
        }) +: cls.modParts.parents.tail
      }
      else cls.modParts.parents

    private def genTransformedCtorParams(cls: ClassTransformData): Seq[Tree] =
      if (cls.data.isAbstract)
        cls.modParts.params
      else if(cls.data.parentIsCRef)
        Seq(q"override val __ref: scalanative.native.CObj.Ref[${cls.data.crefType}]")
      else
        Seq(q"val __ref: scalanative.native.CObj.Ref[${cls.data.crefType}]")

    private def genTransformedTypeBody(t: TypeTransformData[TypeParts]): Seq[Tree] = {
      val companion = t.modParts.companion.get.name
      val imports = Seq(q"import $companion.__ext")
      val ctors =
        if(isAbstract(t))
          Seq(q"def __ref: scalanative.native.CObj.Ref[${t.data.crefType}]")
        else
          genSecondaryConstructor(t)
//      val ctors = Seq(s"val __ref: ${t.data.crefType} = ")
      imports ++ ctors ++ (t.modParts.body map {
        case tree @ DefDef(mods, name, types, args, rettype, rhs) if isExtern(rhs) =>
          val externalName = t.data.externals(name.toString)._1
          genExternalCall(externalName,tree,false,t.data)
        case default => default
      })
    }

    private def genSecondaryConstructor(t: TypeTransformData[TypeParts]): Seq[Tree] = {
      val companion = t.modParts.companion.get.name
      t.data.constructors.map { p =>
        val args = transformExternalCallArgs(p._2)
        DefDef(Modifiers(),
          termNames.CONSTRUCTOR,
          List(),
          List(p._2.toList),
          TypeTree(),
          Block(
            Nil,
            Apply(Ident(termNames.CONSTRUCTOR), List(q"$companion.__ext.${TermName(p._1)}(..$args)"))
          ))
      }
    }

    private def genTransformedCompanionBody(t: TransformData[CommonParts]): Seq[Tree] = {
      t.modParts.body map {
        case tree @ DefDef(mods, name, types, args, rettype, rhs) if isExtern(rhs) =>
          val externalName = t.data.externals(name.toString)._1
          genExternalCall(externalName,tree,t.modParts.isObject,t.data)
        case default => default
      }
    }

    private def genBindingsObject(data: MacroData): Tree = {
      val ctors = data.constructors.map{
        case (externalName,args) => q"def ${TermName(externalName)}(..$args): scalanative.native.CObj.Ref[${data.crefType}] = $expExtern"
      }
      val defs = data.externals.values.map(_._2)
      q"""@scalanative.native.extern object __ext {..${ctors++defs}}"""
    }


    private def genExternalBinding(prefix: String, scalaDef: DefDef, isInstanceMethod: Boolean, data: Data): (String,(String,Tree)) = {
      val scalaName = scalaDef.name.toString
      val externalName = genExternalName(prefix,scalaName)
      val externalParams =
        if(isInstanceMethod) scalaDef.vparamss match {
          case List(params) => List(q"val self: scalanative.native.CObj.Ref[${data.crefType}]" +: transformExternalBindingParams(params) )
          case List(inparams,outparams) =>
            List( q"val self: scalanative.native.CObj.Ref[${data.crefType}]" +:
              (transformExternalBindingParams(inparams) ++ transformExternalBindingParams(outparams,true)) )
          case _ =>
            c.error(c.enclosingPosition,"extern methods with more than two parameter lists are not supported for @CObj classes")
            ???
        }
        else scalaDef.vparamss
      val tpt =
        if(isCRefWrapper(scalaDef.tpt)) tq"scalanative.native.Ptr[Byte]"
        else scalaDef.tpt
      val externalDef = DefDef(scalaDef.mods,TermName(externalName),scalaDef.tparams,externalParams,tpt,scalaDef.rhs)

      (scalaName,(externalName,externalDef))
    }

    private def genExternalCall(externalName: String, scalaDef: DefDef, isClassMethod: Boolean, data: Data): DefDef = {
      import scalaDef._
      val args = vparamss match {
        case Nil => None
        case List(args) => Some(transformExternalCallArgs(args))
        case List(inargs,outargs) =>
          Some( transformExternalCallArgs(inargs) ++ transformExternalCallArgs(outargs) )
        case _ =>
          c.error(c.enclosingPosition,"extern methods with more than two parameter lists are not supported for @CObj classes")
          ???
      }
      val external = TermName(externalName)
      val call = args match {
        case Some(as) if isClassMethod => q"__ext.$external(..$as)"
        case Some(as) => q"__ext.$external(__ref,..$as)"
        case None => q"__ext.$external"
      }
      DefDef(mods,name,tparams,vparamss,tpt,wrapExternalCallResult(call,scalaDef.tpt,data))
    }

    private def wrapExternalCallResult(tree: Tree, tpt: Tree, data: Data): Tree = {
      if (isCRefWrapper(tpt)) {
        q"""new $tpt($tree.cast[scalanative.native.CObj.Ref[Nothing]])"""
        //        q"""${getType(tpt,true).companion}.wrap($tree.cast[scalanative.native.CObj.Ref[Nothing]])"""
      }
      else tree
    }

    private def transformExternalBindingParams(params: List[ValDef], outParams: Boolean = false): List[ValDef] = {
      params map {
        case ValDef(mods,name,tpt,rhs) if isCRefWrapper(tpt) =>
          ValDef(mods,name,q"$tPtrByte",rhs)
//        case ValDef(_,name,tpt,_) if outParams =>
//          c.error(c.enclosingPosition, s"Invalid type for output parameter '$name': output parameters must extend CRef")
//          ???
        case default => default
//        case ValDef(mods,name,tpt,rhs) =>
//          ValDef(mods,name,tpt,EmptyTree)
      }
    }

    private def transformExternalCallArgs(args: Seq[ValDef], outArgs: Boolean = false): Seq[Tree] = {
      args map {
        // TODO: currently crashes due to https://github.com/scala-native/scala-native/issues/1142
        case ValDef(_,name,tpt,_) if isCRefWrapper(tpt) || outArgs => q"$name.__ref.cast[$tPtrByte]"
        case ValDef(_,name,AppliedTypeTree(tpe,_),_) if tpe.toString == "_root_.scala.<repeated>" => q"$name:_*"
        case ValDef(_,name,tpt,_) => q"$name"
      }
    }

    private def isCRefWrapper(tpt: Tree): Boolean =
      try {
        val typed = getType(tpt,true)
        // TODO: do we still need the check for tCRef (or can we only check for tCObjWrapper)
        typed.baseClasses.map(_.asType.toType).exists( t => t <:< tCRef || t <:< tCObjWrapper) ||
          this.findAnnotation(typed.typeSymbol,"scalanative.native.CObj.CRefWrapper").isDefined
      } catch {
        case ex: TypecheckException => false
      }


    def genExternalName(prefix: String, scalaName: String): String =
      prefix + scalaName.replaceAll("([A-Z])","_$1").toLowerCase

    def genPrefixName(clsName: String): String =
      clsName.replaceAll("(.)([A-Z])","$1_$2").toLowerCase + "_"


    private def isExtern(rhs: Tree): Boolean = rhs match {
      case Ident(TermName(id)) =>
        id == "extern"
      case Select(_,name) =>
        name.toString == "extern"
      case x =>
        false
    }

    private def isAbstract(t: TypeTransformData[TypeParts]): Boolean = t match {
      case cls : ClassTransformData => t.modParts.modifiers.hasFlag(Flag.ABSTRACT)
      case _ => false
    }

  }


}