/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Crypto, Satoshi}
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair.blockchain.TestWallet
import fr.acinq.eclair.channel.{CMD_FORCECLOSE, Register, _}
import fr.acinq.eclair.db._
import fr.acinq.eclair.io.Peer.OpenChannel
import fr.acinq.eclair.payment.LocalPaymentHandler
import fr.acinq.eclair.payment.PaymentLifecycle.{ReceivePayment, SendPayment, SendPaymentToRoute}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.RouteCalculationSpec.makeUpdate
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.{Outcome, fixture}
import scodec.bits._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Success

class EclairImplSpec extends TestKit(ActorSystem("mySystem")) with fixture.FunSuiteLike with IdiomaticMockito {

  implicit val timeout = Timeout(30 seconds)

  case class FixtureParam(register: TestProbe, router: TestProbe, paymentInitiator: TestProbe, switchboard: TestProbe, paymentHandler: TestProbe, kit: Kit)

  override def withFixture(test: OneArgTest): Outcome = {
    val watcher = TestProbe()
    val paymentHandler = TestProbe()
    val register = TestProbe()
    val relayer = TestProbe()
    val router = TestProbe()
    val switchboard = TestProbe()
    val paymentInitiator = TestProbe()
    val server = TestProbe()
    val kit = Kit(
      TestConstants.Alice.nodeParams,
      system,
      watcher.ref,
      paymentHandler.ref,
      register.ref,
      relayer.ref,
      router.ref,
      switchboard.ref,
      paymentInitiator.ref,
      server.ref,
      new TestWallet()
    )

    withFixture(test.toNoArgTest(FixtureParam(register, router, paymentInitiator, switchboard, paymentHandler, kit)))
  }

  test("convert fee rate properly") { f =>
    import f._

    val eclair = new EclairImpl(kit)
    val nodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")

    // standard conversion
    eclair.open(nodeId, fundingAmount = Satoshi(10000000L), pushAmount_opt = None, fundingFeerateSatByte_opt = Some(5), flags_opt = None, openTimeout_opt = None)
    val open = switchboard.expectMsgType[OpenChannel]
    assert(open.fundingTxFeeratePerKw_opt == Some(1250))

    // check that minimum fee rate of 253 sat/bw is used
    eclair.open(nodeId, fundingAmount = Satoshi(10000000L), pushAmount_opt = None, fundingFeerateSatByte_opt = Some(1), flags_opt = None, openTimeout_opt = None)
    val open1 = switchboard.expectMsgType[OpenChannel]
    assert(open1.fundingTxFeeratePerKw_opt == Some(MinimumFeeratePerKw))
  }

  test("call send with passing correct arguments") { f =>
    import f._

    val eclair = new EclairImpl(kit)
    val nodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")

    eclair.send(recipientNodeId = nodeId, amount = MilliSatoshi(123), paymentHash = ByteVector32.Zeroes, assistedRoutes = Seq.empty, minFinalCltvExpiryDelta_opt = None)
    val send = paymentInitiator.expectMsgType[SendPayment]
    assert(send.targetNodeId == nodeId)
    assert(send.amount == MilliSatoshi(123))
    assert(send.paymentHash == ByteVector32.Zeroes)
    assert(send.assistedRoutes == Seq.empty)

    // with assisted routes
    val hints = Seq(Seq(ExtraHop(Bob.nodeParams.nodeId, ShortChannelId("569178x2331x1"), feeBaseMsat = 10, feeProportionalMillionths = 1, cltvExpiryDelta = CltvExpiryDelta(12))))
    eclair.send(recipientNodeId = nodeId, amount = MilliSatoshi(123), paymentHash = ByteVector32.Zeroes, assistedRoutes = hints, minFinalCltvExpiryDelta_opt = None)
    val send1 = paymentInitiator.expectMsgType[SendPayment]
    assert(send1.targetNodeId == nodeId)
    assert(send1.amount == MilliSatoshi(123))
    assert(send1.paymentHash == ByteVector32.Zeroes)
    assert(send1.assistedRoutes == hints)

    // with finalCltvExpiry
    eclair.send(recipientNodeId = nodeId, amount = MilliSatoshi(123), paymentHash = ByteVector32.Zeroes, assistedRoutes = Seq.empty, minFinalCltvExpiryDelta_opt = Some(CltvExpiryDelta(96)))
    val send2 = paymentInitiator.expectMsgType[SendPayment]
    assert(send2.targetNodeId == nodeId)
    assert(send2.amount == MilliSatoshi(123))
    assert(send2.paymentHash == ByteVector32.Zeroes)
    assert(send2.finalCltvExpiryDelta == CltvExpiryDelta(96))

    // with custom route fees parameters
    eclair.send(recipientNodeId = nodeId, amount = MilliSatoshi(123), paymentHash = ByteVector32.Zeroes, assistedRoutes = Seq.empty, minFinalCltvExpiryDelta_opt = None, feeThreshold_opt = Some(Satoshi(123)), maxFeePct_opt = Some(4.20))
    val send3 = paymentInitiator.expectMsgType[SendPayment]
    assert(send3.targetNodeId == nodeId)
    assert(send3.amount == MilliSatoshi(123))
    assert(send3.paymentHash == ByteVector32.Zeroes)
    assert(send3.routeParams.get.maxFeeBase == Satoshi(123).toMilliSatoshi) // conversion sat -> msat
    assert(send3.routeParams.get.maxFeePct == 4.20)
  }

  test("allupdates can filter by nodeId") { f =>
    import f._

    val (a, b, c, d, e) = (randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey)

    val updates = List(
      makeUpdate(1L, a, b, feeBase = MilliSatoshi(0), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(13)),
      makeUpdate(4L, a, e, feeBase = MilliSatoshi(0), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(12)),
      makeUpdate(2L, b, c, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(500)),
      makeUpdate(3L, c, d, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(500)),
      makeUpdate(7L, e, c, feeBase = MilliSatoshi(2), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(12))
    ).toMap

    val eclair = new EclairImpl(kit)
    val fResp = eclair.allUpdates(Some(b)) // ask updates filtered by 'b'
    f.router.expectMsg('updatesMap)

    f.router.reply(updates)

    awaitCond({
      fResp.value match {
        // check if the response contains updates only for 'b'
        case Some(Success(res)) => res.forall { u => updates.exists(entry => entry._2.shortChannelId == u.shortChannelId && entry._1.a == b || entry._1.b == b) }
        case _ => false
      }
    })
  }

  test("close and forceclose should work both with channelId and shortChannelId") { f =>
    import f._

    val eclair = new EclairImpl(kit)

    eclair.forceClose(Left(ByteVector32.Zeroes))
    register.expectMsg(Register.Forward(ByteVector32.Zeroes, CMD_FORCECLOSE))

    eclair.forceClose(Right(ShortChannelId("568749x2597x0")))
    register.expectMsg(Register.ForwardShortId(ShortChannelId("568749x2597x0"), CMD_FORCECLOSE))

    eclair.close(Left(ByteVector32.Zeroes), None)
    register.expectMsg(Register.Forward(ByteVector32.Zeroes, CMD_CLOSE(None)))

    eclair.close(Right(ShortChannelId("568749x2597x0")), None)
    register.expectMsg(Register.ForwardShortId(ShortChannelId("568749x2597x0"), CMD_CLOSE(None)))

    eclair.close(Right(ShortChannelId("568749x2597x0")), Some(ByteVector.empty))
    register.expectMsg(Register.ForwardShortId(ShortChannelId("568749x2597x0"), CMD_CLOSE(Some(ByteVector.empty))))
  }

  test("receive should have an optional fallback address and use millisatoshi") { f =>
    import f._

    val fallBackAddressRaw = "muhtvdmsnbQEPFuEmxcChX58fGvXaaUoVt"
    val eclair = new EclairImpl(kit)
    eclair.receive("some desc", Some(MilliSatoshi(123L)), Some(456), Some(fallBackAddressRaw), None)
    val receive = paymentHandler.expectMsgType[ReceivePayment]

    assert(receive.amount_opt == Some(MilliSatoshi(123L)))
    assert(receive.expirySeconds_opt == Some(456))
    assert(receive.fallbackAddress == Some(fallBackAddressRaw))

    // try with wrong address format
    assertThrows[IllegalArgumentException](eclair.receive("some desc", Some(MilliSatoshi(123L)), Some(456), Some("wassa wassa"), None))
  }

  test("passing a payment_preimage to /createinvoice should result in an invoice with payment_hash=H(payment_preimage)") { fixture =>

    val paymentHandler = system.actorOf(LocalPaymentHandler.props(Alice.nodeParams))
    val kitWithPaymentHandler = fixture.kit.copy(paymentHandler = paymentHandler)
    val eclair = new EclairImpl(kitWithPaymentHandler)
    val paymentPreimage = randomBytes32

    val fResp = eclair.receive(description = "some desc", amount_opt = None, expire_opt = None, fallbackAddress_opt = None, paymentPreimage_opt = Some(paymentPreimage))
    awaitCond({
      fResp.value match {
        case Some(Success(pr)) => pr.paymentHash == Crypto.sha256(paymentPreimage)
        case _ => false
      }
    })

  }

  test("networkFees/audit/allinvoices should use a default to/from filter expressed in seconds") { f =>
    import f._

    val auditDb = mock[AuditDb]
    val paymentDb = mock[PaymentsDb]

    auditDb.listNetworkFees(anyLong, anyLong) returns Seq.empty
    auditDb.listSent(anyLong, anyLong) returns Seq.empty
    auditDb.listReceived(anyLong, anyLong) returns Seq.empty
    auditDb.listRelayed(anyLong, anyLong) returns Seq.empty
    paymentDb.listPaymentRequests(anyLong, anyLong) returns Seq.empty

    val databases = mock[Databases]
    databases.audit returns auditDb
    databases.payments returns paymentDb

    val kitWithMockAudit = kit.copy(nodeParams = kit.nodeParams.copy(db = databases))
    val eclair = new EclairImpl(kitWithMockAudit)

    Await.result(eclair.networkFees(None, None), 10 seconds)
    auditDb.listNetworkFees(0, MaxEpochSeconds).wasCalled(once) // assert the call was made only once and with the specified params

    Await.result(eclair.audit(None, None), 10 seconds)
    auditDb.listRelayed(0, MaxEpochSeconds).wasCalled(once)
    auditDb.listReceived(0, MaxEpochSeconds).wasCalled(once)
    auditDb.listSent(0, MaxEpochSeconds).wasCalled(once)

    Await.result(eclair.allInvoices(None, None), 10 seconds)
    paymentDb.listPaymentRequests(0, MaxEpochSeconds).wasCalled(once) // assert the call was made only once and with the specified params
  }

  test("sendtoroute should pass the parameters correctly") { f =>
    import f._

    val route = Seq(PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87"))
    val eclair = new EclairImpl(kit)
    eclair.sendToRoute(route, MilliSatoshi(1234), ByteVector32.One, CltvExpiryDelta(123))

    val send = paymentInitiator.expectMsgType[SendPaymentToRoute]

    assert(send.hops == route)
    assert(send.amount == MilliSatoshi(1234))
    assert(send.finalCltvExpiryDelta == CltvExpiryDelta(123))
    assert(send.paymentHash == ByteVector32.One)
  }

}