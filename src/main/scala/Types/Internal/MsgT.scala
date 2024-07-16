package Types.Internal

import Instances.*
import Types.HandleM
import org.apache.pekko.actor.typed.ActorRef

sealed trait MsgT[A, M, S]
case class RequestMerge[A, M, S](nodeId: ProcId, procId: ProcId, ref: ActorRef[MsgT[A, M, S]]) extends MsgT[A, M, S]
case class Merge[A, M, S](nodeId: ProcId, procId: ProcId, v: Wcrdt[A, S])
    extends MsgT[A, M, S]
case class Process[A, M, S](m: M, stream: S) extends MsgT[A, M, S]
case class SetIdSet[A, M, S](s: Set[Int]) extends MsgT[A, M, S]
case class SetRefs[A, M, S](
    s: Set[ActorRef[MsgT[A, M, S]]]
) extends MsgT[A, M, S]
case class Exec[A, M, S](
    procId: ProcId,
    knownRestartPoint: (WindowId, S, Wcrdt[A, S]),
    handle: HandleM[A, M, S, Unit]
) extends MsgT[A, M, S]
case class transferReplica[A, M, S](
    initCRDT: A,
    proc: (ProcId, HandleM[A, M, S, Unit], S),
    targetRef: ActorRef[MsgT[A, M, S]]
) extends MsgT[A, M, S]
