package Types

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import Instances.Wcrdt
import scalaz.Functor
import scalaz.Monad
import scalaz.Applicative
import scalaz.Scalaz.ToBindOps
import scalaz.Scalaz.ToFunctorOps
import Types.Internal._
import scalaz.StateT.stateTMonadState
import scalaz.MonadTrans
import scalaz.StateT
import scalaz.MonadState
import scalaz.IndexedStateT.StateMonadTrans

sealed trait HandleResult[A, M, C]
case class UpdateCRDT[A, M, C](crdt: Wcrdt[A, Int], carry: C)
    extends HandleResult[A, M, C]
case class ModifyBehavior[A, M, C](b: Behavior[MsgT[A, Int, M]])
    extends HandleResult[A, M, C]
case class Pass[A, M, C](carry: C) extends HandleResult[A, M, C]
case class AwaitWindow[A, M, C](
    w: Int,
    crdt: Wcrdt[A, Int],
    next: A => HandleM_[A, M, C]
) extends HandleResult[A, M, C]

class HandleM_[A, M, C] private[Types] (
    val runHandleM_ : Wcrdt[A, Int] => HandleResult[A, M, C]
)

type HandleM[A, M, C] =
  StateT[(ActorContext[MsgT[A, Int, M]], M), [C] =>> HandleM_[A, M, C], C]

given [A, M, C]: Functor[[C] =>> HandleM_[A, M, C]] with
  def map[C, B](fa: HandleM_[A, M, C])(f: C => B): HandleM_[A, M, B] =
    for {
      x <- fa
    } yield f(x)

given [A, M, C]: Applicative[[C] =>> HandleM_[A, M, C]] with
  def point[C](a: => C): HandleM_[A, M, C] = HandleM_(_ => Pass(a))
  def ap[C, B](fa: => HandleM_[A, M, C])(
      f: => HandleM_[A, M, C => B]
  ): HandleM_[A, M, B] = for {
    f_ <- f
    v <- fa
  } yield f_(v)

given [A, M, C]: Monad[[C] =>> HandleM_[A, M, C]] with
  def point[C](a: => C): HandleM_[A, M, C] = HandleM_(_ => Pass(a))
  def bind[C, B](fa: HandleM_[A, M, C])(
      f: C => HandleM_[A, M, B]
  ): HandleM_[A, M, B] = HandleM_(wcrdt =>
    fa.runHandleM_(wcrdt) match
      case UpdateCRDT(z_, c) =>
        f(c).runHandleM_(z_) match
          case Pass(c_)                   => UpdateCRDT(z_, c_)
          case UpdateCRDT(z__, c_)        => UpdateCRDT(z__, c_)
          case ModifyBehavior(b)          => ModifyBehavior(b)
          case AwaitWindow(w, crdt, next) => AwaitWindow(w, crdt, next)
      case Pass(c)           => f(c).runHandleM_(wcrdt)
      case ModifyBehavior(b) => ModifyBehavior(b)
      case AwaitWindow(w, crdt, next) =>
        AwaitWindow(w, crdt, x => next(x) >>= f)
  )

object HandleM:
  def getContext[A, M]: HandleM[A, M, ActorContext[MsgT[A, Int, M]]] =
    summon[MonadState[?, ?]].get.map(x => x._1)

  def getMsg[A, M]: HandleM[A, M, M] =
    summon[MonadState[?, ?]].get.map(x => x._2)

  def getCRDT[A, M]: HandleM[A, M, Wcrdt[A, Int]] =
    summon[MonadTrans[?]].liftM(HandleM_(c => Pass(c)))

  def putCRDT[A, M]: Wcrdt[A, Int] => HandleM[A, M, Wcrdt[A, Int]] =
    x => summon[MonadTrans[?]].liftM(HandleM_(_ => UpdateCRDT(x, x)))

  def modifyCRDT[A, M]
      : (Wcrdt[A, Int] => Wcrdt[A, Int]) => HandleM[A, M, Wcrdt[A, Int]] =
    f => summon[MonadTrans[?]].liftM(HandleM_(x => UpdateCRDT(f(x), f(x))))

  def liftIO[A, B, M]: (=> B) => HandleM[A, M, B] =
    f => summon[MonadTrans[?]].liftM(HandleM_(_ => Pass(f)))

  def await[A, B, M]: Int => HandleM[A, M, A] =
    w =>
      summon[MonadTrans[?]]
        .liftM(HandleM_(c => AwaitWindow(w, c, x => summon[Monad[?]].point(x))))

  def transformMsg[A, B, M] : (M => M) => HandleM[A, M, Unit] =
    f => 
      summon[MonadState[?,?]].modify((c,m) => (c, f(m)))