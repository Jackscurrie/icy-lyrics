package com.icy.lyrics.core.platform.timing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothDeviceKeyFactoryTest {
  @Test
  fun addressIdentityIsNormalizedHashedAndStable() {
    val first = BluetoothDeviceKeyFactory.create("AA:BB:CC:DD:EE:FF", 8, "Headphones")
    val second = BluetoothDeviceKeyFactory.create("aa:bb:cc:dd:ee:ff", 23, "Renamed")

    assertEquals(first, second)
    assertTrue(first.startsWith("bt:address:"))
    assertTrue("AA:BB" !in first)
  }

  @Test
  fun fallbackUsesTypeAndNormalizedNameAndCanBeDistinguished() {
    val first = BluetoothDeviceKeyFactory.create(null, 8, " My   Headphones ")
    val same = BluetoothDeviceKeyFactory.create("", 8, "my headphones")
    val otherType = BluetoothDeviceKeyFactory.create(null, 26, "my headphones")

    assertEquals(first, same)
    assertNotEquals(first, otherType)
    assertTrue(first.startsWith("bt:fallback:"))
  }
}
