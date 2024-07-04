package Types

import Instances.Wcrdt
import Types.Internal._
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import scalaz.Applicative
import scalaz.Functor
import scalaz.IndexedStateT.StateMonadTrans
import scalaz.Monad
import scalaz.MonadState
import scalaz.MonadTrans
import scalaz.Scalaz._
import scalaz.StateT
import scalaz.StateT.stateTMonadState

sealed trait HandleResult[A, M, C]
case class UpdateCRDT[A, M, C](crdt: Wcrdt[A, Int], carry: C)
    extends HandleResult[A, M, C]
case class ModifyBehavior[A, M, C](b: Behavior[MsgT[A, Int, M]])
    extends HandleResult[A, M, C]
case class Pass[A, M, C](carry: C) extends HandleResult[A, M, C]
case class AwaitWindow[A, M, C](
    w: Int,
    msg: M,
    crdt: Wcrdt[A, Int],
    next: A => HandleM_[A, M, C]
) extends HandleResult[A, M, C]

class HandleM_[A, M, C] private[Types] (
    val runHandleM_ : Wcrdt[A, Int] => HandleResult[A, M, C]
)

type HandleM[A, M, C] =
  StateT[
    (ActorContext[MsgT[A, Int, M]], M, WActorState[A, M]),
    [C] =>> HandleM_[A, M, C],
    C
  ]

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
          case Pass(c_)            => UpdateCRDT(z_, c_)
          case UpdateCRDT(z__, c_) => UpdateCRDT(z__, c_)
          case ModifyBehavior(b)   => ModifyBehavior(b)
          case AwaitWindow(w, msg, crdt, next) =>
            AwaitWindow(w, msg, crdt, next)
      case Pass(c)           => f(c).runHandleM_(wcrdt)
      case ModifyBehavior(b) => ModifyBehavior(b)
      case AwaitWindow(w, msg, crdt, next) =>
        AwaitWindow(w, msg, crdt, x => next(x) >>= f)
  )

object HandleM:
  /** Handle with context
    *
    * Note that there is no getContext beacuse context may change while
    * awaiting. getContext may cause the user to hold an invalid reference.
    *
    * @return
    */
  def liftContextIO[A, M]
      : (ActorContext[MsgT[A, Int, M]] => Unit) => HandleM[A, M, Unit] =
    val getContext: HandleM[A, M, ActorContext[MsgT[A, Int, M]]] =
      summon[MonadState[?, ?]].get.map(x => x._1)
    f => getContext >>= (context => liftIO(f(context)))

  /** Get current message
    *
    * @return
    */
  def getMsg[A, M]: HandleM[A, M, M] =
    summon[MonadState[?, ?]].get.map(x => x._2)

  /** Get current windowed crdt
    *
    * @return
    */
  def getCRDT[A, M]: HandleM[A, M, Wcrdt[A, Int]] =
    summon[MonadTrans[?]].liftM(HandleM_(c => Pass(c)))

  /** Modify current windowed CRDT.
    *
    * This operation must be monotonic and it is user's resoponsiblity to ensure
    * that.
    *
    * @return
    */
  def modifyCRDT[A, M]
      : (Wcrdt[A, Int] => Wcrdt[A, Int]) => HandleM[A, M, Wcrdt[A, Int]] =
    f => summon[MonadTrans[?]].liftM(HandleM_(x => UpdateCRDT(f(x), f(x))))

  /** Lift an IO operation into current context.
    *
    * Although it is possible to use mutable state, it is not recommended.
    * Mutable state can not be recovered while doing fault recovery.
    *
    * @return
    */
  def liftIO[A, B, M]: (=> B) => HandleM[A, M, B] =
    f => summon[MonadTrans[?]].liftM(HandleM_(_ => Pass(f)))

  /** Await for a window's value.
    *
    * This opeartion will block until the value is ready. Or return immediately
    * if it is already there. Blocking is required for confluent property of
    * windowed CRDT.
    *
    * @return
    */
  def await[A, B, M]: Int => HandleM[A, M, A] =
    val getIdSet: HandleM[A, M, Set[Int]] =
      summon[MonadState[?, ?]].get.map(x => x._3.actorIdSet)
    w =>
      getMsg >>= (msg =>
        getIdSet >>= (idSet =>
          // Do not block if value is ready
          summon[MonadTrans[?]]
            .liftM(
              HandleM_(c =>
                c.query(w)(idSet) match
                  case Some(v) => Pass(v)
                  case None =>
                    AwaitWindow(w, msg, c, x => summon[Monad[?]].point(x))
              )
            )
        )
      )

  private[Types] def transformMsg[A, B, M]: (M => M) => HandleM[A, M, Unit] =
    f => summon[MonadState[?, ?]].modify((c, m, s) => (c, f(m), s))
