package dotty.tools
package dotc
package typer

import core._
import ast._
import Contexts._, Types._, Flags._, Denotations._, NameOps._, Symbols._
import Trees._
import annotation.unchecked
import util.Positions._
import Decorators._
import ErrorReporting.InfoString

object Inferencing {

  import tpd._

  /** Is type fully defined, meaning the type does not contain wildcard types
   *  or uninstantiated type variables. As a side effect, this will minimize
   *  any uninstantiated type variables, provided that
   *   - the instance type for the variable is not Nothing or Null
   *   - the overall result of `isFullYDefined` is `true`.
   *  Variables that are succesfully minimized do not count as uninstantiated.
   */
  def isFullyDefined(tp: Type, forceIt: Boolean = false)(implicit ctx: Context): Boolean = {
    val nestedCtx = ctx.fresh.withNewTyperState
    val result = new IsFullyDefinedAccumulator(forceIt)(nestedCtx).traverse(tp)
    if (result) nestedCtx.typerState.commit()
    result
  }

  private class IsFullyDefinedAccumulator(forceIt: Boolean)(implicit ctx: Context) extends TypeAccumulator[Boolean] {
    def traverse(tp: Type): Boolean = apply(true, tp)
    def apply(x: Boolean, tp: Type) = !x || isOK(tp) && foldOver(x, tp)
    def isOK(tp: Type): Boolean = tp match {
      case _: WildcardType =>
        false
      case tvar: TypeVar if forceIt && !tvar.isInstantiated =>
        val inst = tvar.instantiate(fromBelow = true)
        inst != defn.NothingType && inst != defn.NullType
      case _ =>
        true
    }
  }

  def widenForSelector(tp: Type)(implicit ctx: Context): Type = tp.widen match {
    case tp: TypeRef if !tp.symbol.isClass => widenForSelector(tp.bounds.hi)
    case tp => tp
  }

  def checkBounds(args: List[Tree], poly: PolyType, pos: Position)(implicit ctx: Context): Unit = {

  }

  def checkStable(tp: Type, pos: Position)(implicit ctx: Context): Type = {
    if (!tp.isStable)
      ctx.error(i"Prefix $tp is not stable", pos)
    tp
  }

  def checkClassTypeWithStablePrefix(tp: Type, pos: Position)(implicit ctx: Context): ClassSymbol = tp.dealias match {
    case tp: TypeRef if tp.symbol.isClass =>
      checkStable(tp.prefix, pos)
      tp.symbol.asClass
    case _: RefinedType | _: TypeVar | _: AnnotatedType =>
      checkClassTypeWithStablePrefix(tp.asInstanceOf[TypeProxy].underlying, pos)
    case _ =>
      ctx.error(i"$tp is not a class type", pos)
      defn.ObjectClass
  }

  def checkInstantiatable(cls: ClassSymbol, pos: Position): Unit = {
    ???
  }

  implicit class Infer(val ictx: Context) extends AnyVal {

    implicit private def ctx = ictx
    private def state = ctx.typerState

    /** Add all parameters in given polytype `pt` to the constraint's domain.
     *  If the constraint contains already some of these parameters in its domain,
     *  make a copy of the polytype and add the copy's type parameters instead.
     *  Return either the original polytype, or the copy, if one was made.
     */
    def track(pt: PolyType): PolyType = {
      val tracked =
        if (state.constraint contains pt) pt.copy(pt.paramNames, pt.paramBounds, pt.resultType)
        else pt
      state.constraint = state.constraint + tracked
      tracked
    }

    /** Interpolate those undetermined type variables whose position
     *  is included in the position `pos` of the current tree.
     *  If such a variable appears covariantly in type `tp` or does not appear at all,
     *  approximate it by its lower bound. Otherwise, if it appears contravariantly
     *  in type `tp` approximate it by its upper bound.
     */
    def interpolateUndetVars(tp: Type, pos: Position): Unit = {
      val vs = tp.variances(tvar =>
        (ctx.typerState.undetVars contains tvar) && (pos contains tvar.pos))
      for ((tvar, v) <- vs)
        if (v == 1) tvar.instantiate(fromBelow = true)
        else if (v == -1) tvar.instantiate(fromBelow = false)
      for (tvar <- ctx.typerState.undetVars if !(vs contains tvar))
        tvar.instantiate(fromBelow = false)
    }

    /** Instantiate undetermined type variables to that type `tp` is
     *  maximized and return None. If this is not possible, because a non-variant
     *  typevar is not uniquely determined, return that typevar in a Some.
     */
    def maximizeType(tp: Type): Option[TypeVar] = {
      val vs = tp.variances(tvar => ctx.typerState.undetVars contains tvar)
      var result: Option[TypeVar] = None
      for ((tvar, v) <- vs)
        if (v == 1) tvar.instantiate(fromBelow = false)
        else if (v == -1) tvar.instantiate(fromBelow = true)
        else {
          val bounds @ TypeBounds(lo, hi) = ctx.typerState.constraint(tvar.origin)
          if (hi <:< lo) tvar.instantiate(fromBelow = false)
          else result = Some(tvar)
        }
      result
    }

    /** Create new type variables for the parameters of a poly type.
     *  @param pos   The position of the new type variables (relevant for
     *  interpolateUndetVars
     */
    def newTypeVars(pt: PolyType, pos: Position): List[TypeVar] =
      for (n <-  (0 until pt.paramNames.length).toList)
      yield new TypeVar(PolyParam(pt, n), ctx.typerState, pos)

    def isSubTypes(actuals: List[Type], formals: List[Type])(implicit ctx: Context): Boolean = formals match {
      case formal :: formals1 =>
        actuals match {
          case actual :: actuals1 => actual <:< formal && isSubTypes(actuals1, formals1)
          case _ => false
        }
      case nil =>
        actuals.isEmpty
    }

/* not needed right now
    def formalParameters[T](mtp: MethodType, actuals: List[T])(isRepeated: T => Boolean)(implicit ctx: Context) =
      if (mtp.isVarArgs && !(actuals.nonEmpty && isRepeated(actuals.last))) {
        val leading = mtp.paramTypes.init
        val repeated = mtp.paramTypes.last.typeArgs.head
        val trailing = List.fill(actuals.length - leading.length)(repeated)
        leading ++ trailing
      }
      else mtp.paramTypes
  */
  }
}