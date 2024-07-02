package Types

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import Instances.Wcrdt
import scalaz.Functor
import scalaz.Monad
import scalaz.Applicative
import scalaz.Scalaz.ToBindOps
import scalaz.Scalaz.ToFunctorOps

sealed trait HandleResult[A, M]
case class UpdateCRDT[A, M](v: A) extends HandleResult[A, M]
case class ModifyBehavior[A, M](b: Behavior[M]) extends HandleResult[A, M]
case class Pass[A, M]() extends HandleResult[A, M]

class HandleM[A, M, C] private[Types] (
    val runHandleM: ActorContext[MsgT[A, Int, M]] => M => Wcrdt[
      A,
      Int
    ] => (C, HandleResult[Wcrdt[A, Int], MsgT[A, Int, M]])
)

given [A, M, C]: Functor[[C] =>> HandleM[A, M, C]] with
  def map[C, B](fa: HandleM[A, M, C])(f: C => B): HandleM[A, M, B] =
    HandleM(a =>
      b =>
        c =>
          val v = fa.runHandleM(a)(b)(c)
          v.copy(_1 = f(v._1))
    )

given [A, M, C]: Applicative[[C] =>> HandleM[A, M, C]] with
  def point[C](a: => C): HandleM[A, M, C] = HandleM(x => y => z => (a, Pass()))
  def ap[C, B](fa: => HandleM[A, M, C])(
      f: => HandleM[A, M, C => B]
  ): HandleM[A, M, B] = for {
    f_ <- f
    v <- fa
  } yield f_(v)

given [A, M, C]: Monad[[C] =>> HandleM[A, M, C]] with
  def point[C](a: => C): HandleM[A, M, C] = HandleM(x => y => z => (a, Pass()))
  def bind[C, B](fa: HandleM[A, M, C])(
      f: C => HandleM[A, M, B]
  ): HandleM[A, M, B] = HandleM(x =>
    y =>
      z =>
        fa.runHandleM(x)(y)(z) match
          case (c, UpdateCRDT(z_)) => f(c).runHandleM(x)(y)(z_) match
            case (c_, Pass()) => (c_, UpdateCRDT(z_))
            case (c_, UpdateCRDT(z__)) => (c_, UpdateCRDT(z__))
            case (c_, ModifyBehavior(b)) => (c_, ModifyBehavior(b))
          case (c, Pass())         => f(c).runHandleM(x)(y)(z)
          case (c, ModifyBehavior(b)) =>
            f(c).runHandleM(x)(y)(z).copy(_2 = ModifyBehavior(b))
  )

object HandleM:
  def getContext[A, M]: HandleM[A, M, ActorContext[MsgT[A, Int, M]]] =
    HandleM(a => _ => _ => (a, Pass()))

  def getMsg[A, M]: HandleM[A, M, M] =
    HandleM(_ => m => _ => (m, Pass()))

  def getCRDT[A, M]: HandleM[A, M, Wcrdt[A, Int]] =
    HandleM(_ => _ => c => (c, Pass()))

  def putCRDT[A, M]: Wcrdt[A, Int] => HandleM[A, M, Wcrdt[A, Int]] =
    x => HandleM(_ => _ => _ => (x, UpdateCRDT(x)))

  def modifyCRDT[A, M]
      : (Wcrdt[A, Int] => Wcrdt[A, Int]) => HandleM[A, M, Wcrdt[A, Int]] =
    f => HandleM(_ => _ => z => (z, UpdateCRDT(f(z))))

  def liftIO[A, B, M]: (=> B) => HandleM[A, M, B] =
    x => HandleM(_ => m => _ => (x, Pass()))
