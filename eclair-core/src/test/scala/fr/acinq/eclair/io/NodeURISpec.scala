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

package fr.acinq.eclair.io

import org.scalatest.funsuite.AnyFunSuite


class NodeURISpec extends AnyFunSuite {

  val PUBKEY = "03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134"
  val SHORT_PUB_KEY = "03933884aaf1d6b108397e5efe5c86bcf2d8ca"
  val NOT_HEXA_PUB_KEY = "03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fcghijklmn"

  val IPV4_ENDURANCE = "34.250.234.192"
  val NAME_ENDURANCE = "endurance.acinq.co"
  val IPV6 = "[2405:204:66a9:536c:873f:dc4a:f055:a298]"
  val IPV6_NO_BRACKETS = "2001:db8:a0b:12f0::1"
  val IPV6_PREFIX = "[2001:db8:a0b:12f0::1/64]"
  val IPV6_ZONE_IDENTIFIER = "[2001:db8:a0b:12f0::1%eth0]"

  test("default port") {
    assert(NodeURI.DEFAULT_PORT == 9735)
  }

  // ---------- IPV4

  test("parse NodeURI with IPV4 and port") {
    val uri = s"$PUBKEY@$IPV4_ENDURANCE:9737"
    val nodeUri = NodeURI.parse(uri)
    assert(nodeUri.nodeId.toString() == PUBKEY)
    assert(nodeUri.address.getPort == 9737)
  }

  test("parse NodeURI with IPV4 and NO port") {
    val uri = s"$PUBKEY@$IPV4_ENDURANCE"
    val nodeUri = NodeURI.parse(uri)
    assert(nodeUri.address.getPort == NodeURI.DEFAULT_PORT)
  }

  test("parse NodeURI with named host and port") {
    val uri = s"$PUBKEY@$IPV4_ENDURANCE:9737"
    val nodeUri = NodeURI.parse(uri)
    assert(nodeUri.nodeId.toString() == PUBKEY)
    assert(nodeUri.address.getPort == 9737)
  }

  // ---------- IPV6 / regular with brackets

  test("parse NodeURI with IPV6 with brackets and port") {
    val uri = s"$PUBKEY@$IPV6:9737"
    val nodeUri = NodeURI.parse(uri)
    assert(nodeUri.address.getPort == 9737)
  }

  test("parse NodeURI with IPV6 with brackets and NO port") {
    val uri = s"$PUBKEY@$IPV6"
    val nodeUri = NodeURI.parse(uri)
    assert(nodeUri.address.getPort == NodeURI.DEFAULT_PORT)
  }

  // ---------- IPV6 / regular without brackets

  test("fail to parse NodeURI with IPV6 without brackets and port, and use default port") {
    // this can not be parsed because we can not tell what the port is (brackets are required) and the port is the default
    val uri = s"$PUBKEY@$IPV6_NO_BRACKETS:9737"
    val nodeUri = NodeURI.parse(uri)
    assert(nodeUri.address.getPort == NodeURI.DEFAULT_PORT)
  }

  test("parse NodeURI with IPV6 without brackets and NO port") {
    val uri = s"$PUBKEY@$IPV6_NO_BRACKETS"
    val nodeUri = NodeURI.parse(uri)
    assert(nodeUri.address.getPort == NodeURI.DEFAULT_PORT)
  }

  // ---------- IPV6 / prefix

  test("parse NodeURI with IPV6 with prefix and port") {
    val uri = s"$PUBKEY@$IPV6_PREFIX:9737"
    val nodeUri = NodeURI.parse(uri)
    assert(nodeUri.address.getPort == 9737)
  }

  test("parse NodeURI with IPV6 with prefix and NO port") {
    val uri = s"$PUBKEY@$IPV6_PREFIX"
    val nodeUri = NodeURI.parse(uri)
    assert(nodeUri.address.getPort == NodeURI.DEFAULT_PORT)
  }

  // ---------- IPV6 / zone identifier

  test("parse NodeURI with IPV6 with a zone identifier and port") {
    val uri = s"$PUBKEY@$IPV6_ZONE_IDENTIFIER:9737"
    val nodeUri = NodeURI.parse(uri)
    assert(nodeUri.address.getPort == 9737)
  }

  test("parse NodeURI with IPV6 with a zone identifier and NO port") {
    val uri = s"$PUBKEY@$IPV6_ZONE_IDENTIFIER"
    val nodeUri = NodeURI.parse(uri)
    assert(nodeUri.address.getPort == NodeURI.DEFAULT_PORT)
  }

  // ---------- fail if public key is not valid

  test("parsing should fail if the public key is not correct") {
    intercept[IllegalArgumentException](NodeURI.parse(s"$SHORT_PUB_KEY@$IPV4_ENDURANCE"))
    intercept[IllegalArgumentException](NodeURI.parse(s"$NOT_HEXA_PUB_KEY@$IPV4_ENDURANCE"))
  }

  // ---------- fail if host:port is not valid

  test("parsing should fail if host:port is not valid") {
    intercept[IllegalArgumentException](NodeURI.parse(s"$SHORT_PUB_KEY@1.2.3.4:abcd"))
    intercept[IllegalArgumentException](NodeURI.parse(s"$SHORT_PUB_KEY@1.2.3.4:999999999999999999999"))
  }

  test("parsing should fail if the uri is malformed") {
    intercept[IllegalArgumentException](NodeURI.parse("03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134@"))
    intercept[IllegalArgumentException](NodeURI.parse("03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134@123.45@654321"))
    intercept[IllegalArgumentException](NodeURI.parse("loremipsum"))
    intercept[IllegalArgumentException](NodeURI.parse(IPV6))
    intercept[IllegalArgumentException](NodeURI.parse(IPV4_ENDURANCE))
    intercept[IllegalArgumentException](NodeURI.parse(PUBKEY))
    intercept[IllegalArgumentException](NodeURI.parse(""))
    intercept[IllegalArgumentException](NodeURI.parse("@"))
    intercept[IllegalArgumentException](NodeURI.parse(":"))
  }

}



