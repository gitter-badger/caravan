package caravan.bus.wishbone
import chisel3._
import chisel3.experimental.DataMirror
import chisel3.stage.ChiselStage
import chisel3.util.{Decoupled, Enum, MuxCase}


// Support only for Single READ/WRITE cycles for now
class WishboneHost(implicit val config: WishboneConfig) extends Module {
  val io = IO(new Bundle {
    val wbMasterTransmitter = Decoupled(new WishboneMaster())
    val wbSlaveReceiver  = Flipped(Decoupled(new WishboneSlave()))
    val reqIn = Flipped(Decoupled(new Request()))
    val rspOut = Decoupled(new Response())
  })

  def fire(): Bool = io.reqIn.valid && io.wbMasterTransmitter.ready
  /**
   * Since valid indicates a valid request, the stb signal from wishbone
   * also indicates the same. So stb and valid are connected together.
   */
  io.wbMasterTransmitter.valid := io.wbMasterTransmitter.bits.stb

  /** FIXME: Assuming Master is always ready to accept data from Slave */
  io.wbSlaveReceiver.ready := true.B
  dontTouch(io.wbMasterTransmitter.ready)
  dontTouch(io.wbSlaveReceiver.ready)

  when(reset.asBool() === true.B) {
    /**
     * Rule 3.20: Following signals must be negated when reset is asserted:
     * stb_o
     * cyc_o
     * all other signals are in an undefined state
     */
    io.wbMasterTransmitter.bits.getElements.filter(w => DataMirror.directionOf(w) == ActualDirection.Output).map(_ := 0.U)
  }
  val startWBReadTransaction = RegInit(false.B)
  val startWBWriteTransaction = RegInit(false.B)
  // registers used to provide the response to the ip.
  val dataReg = RegInit(0.U(config.dataWidth.W))
  val respReg = RegInit(false.B)
  // state machine to conform to the wishbone protocol of negating stb and cyc when data latched
  val idle :: latch_data :: Nil = Enum(2)
  val stateReg = RegInit(idle)

  /** used to pass ready signal to the ip
   * is ready by default
   * de-asserts ready when a valid request is made and slave accepts it (fire)
   * re-asserts ready when the response data from slave is being latched to start another req. */
  val readyReg = RegInit(true.B)
  when(fire) {
    readyReg := false.B
  }
  when(stateReg === latch_data) {
    readyReg := true.B
  }

  if(!config.waitState) {
    /**
     * If host does not produce wait states then stb_o and cyc_o may be assigned the same signal.
     */

    // master is only ready to accept req when any prev req not pending
    io.reqIn.ready := readyReg
    when(io.reqIn.bits.isWrite === false.B && fire) {
      startWBReadTransaction := true.B
    } .elsewhen(io.reqIn.bits.isWrite === true.B && fire) {
      startWBWriteTransaction := true.B
    }

    when(startWBReadTransaction) {
      /**
       * SINGLE READ CYCLE
       * host asserts adr_o, we_o, sel_o, stb_o and cyc_o
       */
      io.wbMasterTransmitter.bits.stb := true.B
      io.wbMasterTransmitter.bits.cyc := io.wbMasterTransmitter.bits.stb
      io.wbMasterTransmitter.bits.we := io.reqIn.bits.isWrite
      io.wbMasterTransmitter.bits.adr := io.reqIn.bits.addrRequest
      io.wbMasterTransmitter.bits.dat := 0.U
      io.wbMasterTransmitter.bits.sel := io.reqIn.bits.activeByteLane
    } .elsewhen(startWBWriteTransaction) {
      /**
       * SINGLE WRITE CYCLE
       *
       */
      io.wbMasterTransmitter.bits.stb := true.B
      io.wbMasterTransmitter.bits.cyc := io.wbMasterTransmitter.bits.stb
      io.wbMasterTransmitter.bits.we := io.reqIn.bits.isWrite
      io.wbMasterTransmitter.bits.adr := io.reqIn.bits.addrRequest
      io.wbMasterTransmitter.bits.dat := io.reqIn.bits.dataRequest
      io.wbMasterTransmitter.bits.sel := io.reqIn.bits.activeByteLane
    } .otherwise {
      io.wbMasterTransmitter.bits.getElements.filter(w => DataMirror.directionOf(w) == ActualDirection.Output).map(_ := 0.U)
    }

    when(io.wbSlaveReceiver.bits.ack) {
      dataReg := io.wbSlaveReceiver.bits.dat
      respReg := true.B
      // making the registers false when ack received so that in the next cycle stb, cyc and other signals get low
      startWBReadTransaction := false.B
      startWBWriteTransaction := false.B
    }

    when(stateReg === idle) {
      stateReg := Mux(io.wbSlaveReceiver.bits.ack, latch_data, idle)
    } .elsewhen(stateReg === latch_data) {
      respReg := false.B
      stateReg := idle
    }

    /** FIXME: not using the ready signal from the IP to send valid data
     * assuming IP is always ready to accept data from the bus */
    io.rspOut.valid := respReg
    io.rspOut.bits.dataResponse := dataReg
  }





  /**
   * Host initiates the transfer cycle by asserting cyc_o. When cyc_o is negated, all other
   * host signals are invalid.
   *
   * Device interface only respond to other device signals only when cyc_i is asserted.
   */

  /**
   * Rule 3.25: Host interfaces MUST assert cyc_o for the duration of SINGLE READ/WRITE, BLOCK and RMW cycles.
   * cyc_o must be asserted in the same rising edge that qualifies the assertion of stb_o
   * cyc_o must be negated in the same rising edge that qualifies the negation of stb_o
   */

  /**
   * Host asserts stb_o when it is ready to transfer data.
   * stb_o remains asserted until the device asserts one of its cycle termination signals:
   * ack_i
   * err_i
   * rty_i
   *
   * if any of the above signals are asserted then the stb_o is negated.
   */

  /**
   * Rule 3.60: Host interfaces must qualify the following signals with stb_o:
   * adr_o
   * dat_mosi
   * sel_o
   * we_o
   * tagn_o
   */


}

object WishboneHostDriver extends App {
  implicit val config = WishboneConfig(addressWidth = 32, dataWidth = 32)
  println((new ChiselStage).emitVerilog(new WishboneHost()))
}