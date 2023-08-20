package fpgatidbits.SimUtils

import chisel3._
import fpgatidbits.dma._

class HighLatencyMemParams(
  val depth: Int,
  val numPorts: Int,
  val dataWidth: Int,
  val addrWidth: Int,
  val idWidth: Int,
  val latency: Int
) {

  override def clone = {
    new HighLatencyMemParams( depth, numPorts, dataWidth, addrWidth,
                              idWidth, latency).asInstanceOf[this.type]
  }

  def toMemReqParams() = {
    new MemReqParams(addrWidth, dataWidth, idWidth, 1)
  }
}

class HighLatencyMem(val p: HighLatencyMemParams) extends Module {
  val pReq = p.toMemReqParams()
  val reqType = new GenericMemoryRequest(pReq)
  val rspType = new GenericMemoryResponse(pReq)

  val io = new Bundle {
    // dedicated port for testbench reads/writes
    val memAddr = UInt(INPUT, p.addrWidth)
    val memWriteEn = Bool(INPUT)
    val memWriteData = UInt(INPUT, p.dataWidth)
    val memReadData = UInt(OUTPUT, p.dataWidth)
    // ports for accelerator accesss
    val memPort = Vec.fill(p.numPorts) {new GenericMemorySlavePort(pReq)}
  }

  // the memory
  val mem = Mem(p.depth, UInt(p.dataWidth.W))
  val memUnitBytes = UInt(p.dataWidth/8)

  // testbench memory access
  def addrToWord(x: UInt) = {x >> UInt(log2Up(p.dataWidth/8))}
  val memWord = addrToWord(io.memAddr)
  io.memReadData := mem(memWord)

  when (io.memWriteEn) {mem(memWord) := io.memWriteData}

  // add lateny between a producer-consumer pair using 2-deep queues
  def addLatency[T <: Data](n: Int, prod: DecoupledIO[T]): DecoupledIO[T] = {
    if(n == 1) {
      return Queue(prod, 2)
    } else {
      return addLatency(n-1, Queue(prod, 2))
    }
  }

  // accelerator memory access ports
  // one FSM per port, rather simple, but supports bursts
  for(i <- 0 until p.numPorts) {
    // reads
    val sWaitRd :: sRead :: Nil = Enum(UInt(), 2)
    val regStateRead = Reg(init = UInt(sWaitRd))
    val regReadRequest = Reg(init = GenericMemoryRequest(pReq))

    val accmp = io.memPort(i)
    val accRdReq = addLatency(p.latency, accmp.memRdReq)
    val accRdRsp = accmp.memRdRsp

    accRdReq.ready := false.B
    accRdRsp.valid := false.B
    accRdRsp.bits.channelID := regReadRequest.channelID
    accRdRsp.bits.metaData := 0.U
    accRdRsp.bits.isWrite := false.B
    accRdRsp.bits.isLast := false.B
    accRdRsp.bits.readData := mem(addrToWord(regReadRequest.addr))

    switch(regStateRead) {
      is(sWaitRd) {
        accRdReq.ready := true.B
        when (accRdReq.valid) {
          regReadRequest := accRdReq.bits
          regStateRead := sRead
        }
      }

      is(sRead) {
        when(regReadRequest.numBytes === 0.U) {
          // prefetch the read request if possible to minimize waiting
          accRdReq.ready := true.B
          when (accRdReq.valid) {
            regReadRequest := accRdReq.bits
            // stay in this state and continue processing
          } .otherwise {regStateRead := sWaitRd}
        }
        .otherwise {
          accRdRsp.valid := true.B
          accRdRsp.bits.isLast := (regReadRequest.numBytes === memUnitBytes)
          when (accRdRsp.ready) {
            regReadRequest.numBytes := regReadRequest.numBytes - memUnitBytes
            regReadRequest.addr := regReadRequest.addr + UInt(memUnitBytes)

            // was this the last beat of burst transferred?
            when(regReadRequest.numBytes === memUnitBytes) {
              // prefetch the read request if possible to minimize waiting
              accRdReq.ready := true.B
              when (accRdReq.valid) {
                regReadRequest := accRdReq.bits
                // stay in this state and continue processing
              }
            }
          }
        }
      }
    }

    // writes
    val sWaitWr :: sWrite :: Nil = Enum(UInt(), 2)
    val regStateWrite = RegInit(sWaitWr)
    val regWriteRequest = RegInit(GenericMemoryRequest(pReq))
    // write data queue to avoid deadlocks (state machine expects rspQ and data
    // available simultaneously)
    val wrDatQ = Module(new Queue(UInt(p.dataWidth.W), 16)).io
    wrDatQ.enq <> accmp.memWrDat

    // queue on write response port (to avoid combinational loops)
    val wrRspQ = Module(new Queue(GenericMemoryResponse(pReq), 16)).io
    wrRspQ.deq <> accmp.memWrRsp

    val accWrReq = addLatency(10, accmp.memWrReq)

    accWrReq.ready := false.B
    wrDatQ.deq.ready := false.B
    wrRspQ.enq.valid := false.B
    wrRspQ.enq.bits.driveDefaults()
    wrRspQ.enq.bits.channelID := regWriteRequest.channelID

    switch(regStateWrite) {
      is(sWaitWr) {
        accWrReq.ready := true.B
        when(accWrReq.valid) {
          regWriteRequest := accWrReq.bits
          regStateWrite := sWrite
        }
      }

      is(sWrite) {
        when(regWriteRequest.numBytes === 0.U) {regStateWrite := sWaitWr}
        .otherwise {
          when(wrRspQ.enq.ready && wrDatQ.deq.valid) {
            when(regWriteRequest.numBytes === memUnitBytes) {
              wrRspQ.enq.valid := true.B
            }
            wrDatQ.deq.ready := true.B
            mem(addrToWord(regWriteRequest.addr)) := wrDatQ.deq.bits
            regWriteRequest.numBytes := regWriteRequest.numBytes - memUnitBytes
            regWriteRequest.addr := regWriteRequest.addr + UInt(memUnitBytes)
          }
        }
      }
    }
  }
}
