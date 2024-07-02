package Types

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import Instances.Wcrdt

sealed trait HandleResult[A, M]
case class UpdateCRDT[A, M](v: A) extends HandleResult[A, M]
case class ModifyBehavior[A, M](b: Behavior[M]) extends HandleResult[A, M]
case class Pass[A, M]() extends HandleResult[A, M]

type Handle[A, M] = ActorContext[MsgT[A, Int, M]] => M => Wcrdt[
  A,
  Int
] => HandleResult[Wcrdt[A, Int], MsgT[A, Int, M]]