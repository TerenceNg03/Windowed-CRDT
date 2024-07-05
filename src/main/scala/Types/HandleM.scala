package Types

import Instances.LocalWin
import Instances.Wcrdt
import Types.Internal.*
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import scalaz.Applicative
import scalaz.Functor
import scalaz.IndexedStateT.StateMonadTrans
import scalaz.Monad
import scalaz.MonadState
import scalaz.MonadTrans
import scalaz.Scalaz.*
import scalaz.StateT
import scalaz.StateT.stateTMonadState
import Instances.WindowID

sealed trait HandleResult[A, M, C]
case class UpdateCRDT[A, M, C](crdt: Wcrdt[A, Stream[M]], carry: C)
    extends HandleResult[A, M, C]
case class ModifyBehavior[A, M, C](b: Behavior[MsgT[A, M]])
    extends HandleResult[A, M, C]
case class Pass[A, M, C](carry: C) extends HandleResult[A, M, C]
case class AwaitWindow[A, M, C](
    w: Int,
    msg: M,
    crdt: Wcrdt[A, Stream[M]],
    next: A => HandleM_[A, M, C]
) extends HandleResult[A, M, C]

class HandleM_[A, M, C] private[Types] (
    val runHandleM_ : Wcrdt[A, Stream[M]] => HandleResult[A, M, C]
)

type HandleM[A, M, C] =
  StateT[
    (ActorContext[MsgT[A, M]], M, WActorState[A, M]),
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
  /** Does nothing.
    *
    * A shortcut for point(())
    *
    * @return
    */
  def void[A, M]: HandleM[A, M, Unit] =
    summon[Monad[[C] =>> HandleM[A, M, C]]].point(())

  /** Handle with context
    *
    * Note that there is no getContext beacuse context may change while
    * awaiting. getContext may cause the user to hold an invalid reference.
    *
    * @return
    */
  def liftContextIO[A, M]
      : (ActorContext[MsgT[A, M]] => Unit) => HandleM[A, M, Unit] =
    val getContext: HandleM[A, M, ActorContext[MsgT[A, M]]] =
      summon[MonadState[?, ?]].get.map(x => x._1)
    f => getContext >>= (context => liftIO(f(context)))

  /** Get current message
    *
    * @return
    */
  def getMsg[A, M]: HandleM[A, M, M] =
    summon[MonadState[?, ?]].get.map(x => x._2)

  private def getCRDT[A, M]: HandleM[A, M, Wcrdt[A, Stream[M]]] =
    summon[MonadTrans[?]].liftM(HandleM_(c => Pass(c)))

  /** Modify current windowed CRDT.
    *
    * This operation must be monotonic and it is user's resoponsiblity to ensure
    * that.
    *
    * @return
    */
  def modifyCRDT[A, M]: (A => A) => HandleM[A, M, Unit] =
    f => summon[MonadTrans[?]].liftM(HandleM_(x => UpdateCRDT(x.update(f), ())))

  /**
    * Go to next window
    *
    * @return
    */
  def nextWindow[A: CRDT, M]: HandleM[A, M, Unit] = 
    summon[MonadTrans[?]].liftM(HandleM_(x => UpdateCRDT(x.nextWindow(), ())))

  /**
    * Read current window number
    *
    * @return
    */
  def currentWindow[A, M]: HandleM[A, M, WindowID] = 
    summon[MonadTrans[?]].liftM(HandleM_(x => Pass(x.window.v)))

  /** Lift an IO operation into current context.
    *
    * Although it is possible to use mutable state, it is not recommended.
    * Mutable state can not be recovered while doing fault recovery.
    *
    * @return
    */
  def liftIO[A, M, B]: (=> B) => HandleM[A, M, B] =
    f => summon[MonadTrans[?]].liftM(HandleM_(_ => Pass(f)))

  /** Await for a window's value.
    *
    * This opeartion will block until the value is ready. Or return immediately
    * if it is already there. Blocking is required for confluent property of
    * windowed CRDT.
    *
    * @return
    */
  def await[A, M]: Int => HandleM[A, M, A] =
    val wait: Int => M => WActorState[A, M] => HandleM[A, M, A] = w =>
      msg =>
        state =>
          summon[MonadTrans[?]]
            .liftM(
              HandleM_(c =>
                c.query(w)(state.actorIdSet) match
                  case Some(v) => Pass(v)
                  case None =>
                    AwaitWindow(w, msg, c, x => summon[Monad[?]].point(x))
              )
            )
    w =>
      for {
        msg <- getMsg
        state <- getState
        c <- getCRDT
        // Detect deadlock for wait on a window itself has not reached
        _ <-
          if c.window.v <= w then
            liftContextIO[A, M](ctx =>
              ctx.log.error(
                s"[Deadlock detected] Actor ${c.procID} " +
                  s"is waiting for window $w while itself is " +
                  s"currently at window ${c.window}"
              )
            )
          else void
        // Do not block if value is ready
        w <- wait(w)(msg)(state)
      } yield w

  /** Update state when a new message arrives
    *   - Update MsgRef in Wcrdt
    *   - Update Msg in StateT
    *   - Send a message to itself of the next msg in the stream
    *
    * @return
    */
  private[Types] def updateStateOnMsg[A, M]
      : M => Stream[M] => HandleM[A, M, Unit] =
    msg =>
      stream =>
        val replaceStream: HandleM[A, M, Unit] = summon[MonadTrans[?]]
          .liftM(
            HandleM_(c => UpdateCRDT(c.copy(msgRef = LocalWin(stream)), ()))
          )
        for {
          _ <- replaceStream
          _ <- summon[MonadState[?, ?]].modify((c, _, s) =>
            (c, msg, s)
          ): HandleM[A, M, Unit]
          state <- getState
          _ <- stream.take(1).toList match
            case x :: _ =>
              liftContextIO[A, M](ctx =>
                ctx.log.debug(
                  s"Actor ${state.actorId} queued a new message to mailbox: $x"
                )
              ) >>
                liftIO[A, M, Unit](
                  state.actorRefs(state.actorId) ! Process(x, stream.tail)
                )
            case _ => liftContextIO[A, M](ctx =>
                ctx.log.debug(
                  s"Actor ${state.actorId} has finished the stream."
                )
              )
        } yield ()

  private def getState[A, M]: HandleM[A, M, WActorState[A, M]] =
    summon[MonadState[?, ?]].get.map(x => x._3)
