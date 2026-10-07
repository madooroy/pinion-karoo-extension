#!/usr/bin/env python3
"""Fake Pinion Smart.Shift gearbox, for testing the Karoo extension without the real one.

Runs on a Linux machine with a spare Bluetooth controller (written for the Raspberry Pi and
USB dongle of the SmartRow bridge) and needs Bumble and root:

    sudo systemctl stop smartrow-bridge
    sudo /opt/smartrow-bridge/venv/bin/python fake_pinion.py
    sudo systemctl start smartrow-bridge      # when done

It speaks the protocol in ../CLAUDE.md: the gear is notified on its own characteristic, and
everything else is request/response. Like the real gearbox it advertises for two minutes after
"power on", and after a disconnect it stays silent until the pairing button is "held" (key p).

Keys:
    up / down (or + / -)   shift one gear
    <number> Enter         jump to that gear
    <number> b             set the battery to that many percent
    a                      toggle automatic sweeping through the gears
    p                      pairing button: advertise for two minutes
    x                      drop the connection, as when riding out of range
    q                      quit
"""

from __future__ import annotations

import argparse
import asyncio
import fcntl
import logging
import os
import socket
import struct
import sys
import termios
import tty

from bumble.core import UUID
from bumble.device import Connection, Device
from bumble.gatt import Characteristic, CharacteristicValue, Service
from bumble.hci import Address
from bumble.transport import open_transport

log = logging.getLogger("fake-pinion")

SERVICE_UUID = "00000000-33d2-4f94-9ee4-9312b3660005"
CURRENT_GEAR_UUID = "00000001-33d2-4f94-9ee4-9312b3660005"
REQUEST_UUID = "0000000d-33d2-4f94-9ee4-9312b3660005"
RESPONSE_UUID = "0000000e-33d2-4f94-9ee4-9312b3660005"

OP_ERROR, OP_READ, OP_REPLY, OP_WRITE, OP_ACK = 0x00, 0x01, 0x02, 0x03, 0x04

# Parameter addresses as they appear on the wire
ADDR_HARDWARE_VERSION = bytes([0x09, 0x10, 0x00])
ADDR_FIRMWARE_VERSION = bytes([0x56, 0x1F, 0x01])
ADDR_BOOTLOADER_VERSION = bytes([0x56, 0x1F, 0x02])
ADDR_SERIAL_NUMBER = bytes([0x18, 0x10, 0x04])
ADDR_CURRENT_GEAR = bytes([0x01, 0x61, 0x02])
ADDR_BATTERY_LEVEL = bytes([0x64, 0x61, 0x01])
ADDR_NUMBER_OF_GEARS = bytes([0x00, 0x25, 0x00])

ADVERTISING_WINDOW_S = 120
ADVERTISING_INTERVAL_MS = 100

_BTPROTO_HCI = 1
_HCIDEVDOWN = 0x400448CA  # _IOW('H', 202, int)


def find_usb_adapter() -> int:
    """Index of the first USB Bluetooth controller (the dongle, not the Pi's built-in UART radio)."""
    base = "/sys/class/bluetooth"
    for name in sorted(os.listdir(base)):
        if name.startswith("hci") and name[3:].isdigit() and "/usb" in os.path.realpath(os.path.join(base, name)):
            return int(name[3:])
    raise SystemExit("No USB Bluetooth adapter found; pass --hci N")


def release_from_bluez(index: int) -> None:
    """Bring the adapter down so Bumble can open an HCI user channel on it."""
    try:
        with socket.socket(socket.AF_BLUETOOTH, socket.SOCK_RAW, _BTPROTO_HCI) as sock:
            fcntl.ioctl(sock.fileno(), _HCIDEVDOWN, index)
    except OSError as exc:
        log.warning("Could not bring hci%d down: %s", index, exc)


def ad_structure(ad_type: int, data: bytes) -> bytes:
    return bytes([len(data) + 1, ad_type]) + data


class FakeGearbox:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.gear_count = args.gears
        self.gear = min(args.gear, self.gear_count)
        self.battery_percent = args.battery
        self.device: Device | None = None
        self.quit = asyncio.Event()
        self._advertising_timeout: asyncio.Task | None = None
        self._auto: asyncio.Task | None = None
        self._digits = ""

        self.gear_char = Characteristic(
            UUID(CURRENT_GEAR_UUID),
            Characteristic.Properties.NOTIFY,
            Characteristic.READABLE,
            bytes([self.gear]),
        )
        self.request_char = Characteristic(
            UUID(REQUEST_UUID),
            Characteristic.Properties.WRITE,
            Characteristic.WRITEABLE,
            CharacteristicValue(write=self._on_request),
        )
        self.response_char = Characteristic(
            UUID(RESPONSE_UUID),
            Characteristic.Properties.INDICATE,
            Characteristic.READABLE,
            b"",
        )
        self.gear_char.on("subscription", lambda c, n, i: self._on_subscription("gear", c, n, i))
        self.response_char.on("subscription", lambda c, n, i: self._on_subscription("response", c, n, i))

    # ------------------------------------------------------------ protocol

    def _parameters(self) -> dict[bytes, tuple[int, int]]:
        """address -> (length, value)"""
        return {
            ADDR_HARDWARE_VERSION: (4, 0x01000000),
            ADDR_FIRMWARE_VERSION: (4, 0x01020304),
            ADDR_BOOTLOADER_VERSION: (4, 0x01000000),
            ADDR_SERIAL_NUMBER: (4, self.args.serial),
            ADDR_CURRENT_GEAR: (1, self.gear),
            ADDR_BATTERY_LEVEL: (2, self.battery_percent * 100),
            ADDR_NUMBER_OF_GEARS: (1, self.gear_count),
        }

    def reply_to(self, request: bytes) -> bytes:
        if len(request) < 5:
            return bytes([OP_ERROR]) + request[1:]
        op, address = request[0], request[2:5]
        parameter = self._parameters().get(address)
        if op == OP_READ and parameter is not None:
            length, value = parameter
            return bytes([OP_REPLY, length]) + address + value.to_bytes(length, "little")
        if op == OP_WRITE:
            # Settings are accepted and ignored
            return bytes([OP_ACK, 0xFF]) + address
        return bytes([OP_ERROR, request[1]]) + address

    def _on_request(self, connection: Connection, value: bytes) -> None:
        request = bytes(value)
        reply = self.reply_to(request)
        log.info("request [%s] -> reply [%s]", request.hex(" "), reply.hex(" "))
        asyncio.get_running_loop().create_task(
            self.device.indicate_subscriber(connection, self.response_char, reply)
        )

    def _on_subscription(self, what: str, connection: Connection, notify: bool, indicate: bool) -> None:
        mode = "notify" if notify else "indicate" if indicate else "off"
        log.info("%s characteristic subscription: %s", what, mode)

    async def set_gear(self, gear: int) -> None:
        gear = max(1, min(self.gear_count, gear))
        if gear == self.gear:
            return
        self.gear = gear
        self.gear_char.value = bytes([gear])
        log.info("gear %d%s", gear, "" if self.device.connections else "  (nobody connected)")
        await self.device.notify_subscribers(self.gear_char, bytes([gear]))

    # --------------------------------------------------------- advertising

    async def press_pairing_button(self) -> None:
        if self.device.connections:
            log.info("pairing button: ignored, a central is connected")
            return
        if self._advertising_timeout is not None:
            self._advertising_timeout.cancel()
        if not self.device.is_advertising:
            await self.device.start_advertising(auto_restart=False)
        if self.args.always_advertise:
            log.info("advertising as %s", self.args.address)
        else:
            log.info("advertising as %s for %d s (LED flashes blue)", self.args.address, ADVERTISING_WINDOW_S)
            self._advertising_timeout = asyncio.create_task(self._stop_advertising_later())

    async def _stop_advertising_later(self) -> None:
        await asyncio.sleep(ADVERTISING_WINDOW_S)
        if self.device.is_advertising:
            await self.device.stop_advertising()
            log.info("advertising window over; press p to advertise again")

    def _on_connection(self, connection: Connection) -> None:
        log.info("central connected: %s", connection.peer_address)
        if self._advertising_timeout is not None:
            self._advertising_timeout.cancel()

        def on_disconnection(reason: int) -> None:
            log.info("central disconnected (reason 0x%02X)", reason)
            if self.args.always_advertise:
                asyncio.get_running_loop().create_task(self.press_pairing_button())
            else:
                log.info("not advertising, like the real gearbox; press p for the pairing button")

        connection.on("disconnection", on_disconnection)

    async def drop_connection(self) -> None:
        if self._advertising_timeout is not None:
            self._advertising_timeout.cancel()
        if self.device.is_advertising:
            await self.device.stop_advertising()
        connections = list(self.device.connections.values())
        if not connections:
            log.info("drop: nobody connected; advertising stopped")
        for connection in connections:
            log.info("dropping %s", connection.peer_address)
            await connection.disconnect()

    # ------------------------------------------------------------ controls

    def toggle_auto(self) -> None:
        if self._auto is not None:
            self._auto.cancel()
            self._auto = None
            log.info("auto sweep off")
        else:
            self._auto = asyncio.create_task(self._auto_sweep())
            log.info("auto sweep on, one shift every %.1f s", self.args.auto_interval)

    async def _auto_sweep(self) -> None:
        step = 1
        while True:
            await asyncio.sleep(self.args.auto_interval)
            if not 1 <= self.gear + step <= self.gear_count:
                step = -step
            await self.set_gear(self.gear + step)

    def _run(self, coroutine) -> None:
        task = asyncio.get_running_loop().create_task(coroutine)
        task.add_done_callback(lambda t: t.cancelled() or (t.exception() and log.error("%r", t.exception())))

    def on_keys(self) -> None:
        data = os.read(sys.stdin.fileno(), 64)
        for arrow, step in ((b"\x1b[A", 1), (b"\x1b[B", -1)):
            while arrow in data:
                data = data.replace(arrow, b"", 1)
                self._run(self.set_gear(self.gear + step))
        for key in data.decode(errors="ignore"):
            if key.isdigit():
                self._digits += key
                continue
            digits, self._digits = self._digits, ""
            if key in "\r\n" and digits:
                self._run(self.set_gear(int(digits)))
            elif key == "b" and digits:
                self.battery_percent = max(0, min(100, int(digits)))
                log.info("battery %d %% (the extension reads it once a minute)", self.battery_percent)
            elif key in "+=":
                self._run(self.set_gear(self.gear + 1))
            elif key in "-_":
                self._run(self.set_gear(self.gear - 1))
            elif key == "a":
                self.toggle_auto()
            elif key == "p":
                self._run(self.press_pairing_button())
            elif key == "x":
                self._run(self.drop_connection())
            elif key == "q":
                self.quit.set()

    # ----------------------------------------------------------- lifecycle

    async def run(self) -> None:
        if self.args.transport:
            transport_spec = self.args.transport
        else:
            index = self.args.hci if self.args.hci is not None else find_usb_adapter()
            release_from_bluez(index)
            transport_spec = f"hci-socket:{index}"
        log.info("opening %s", transport_spec)
        transport = await open_transport(transport_spec)
        try:
            self.device = Device.with_hci(self.args.name, Address(self.args.address), transport.source, transport.sink)
            self.device.add_services([Service(UUID(SERVICE_UUID), [self.gear_char, self.request_char, self.response_char])])
            # The extension finds the gearbox by this service UUID in the advertisement
            self.device.advertising_data = ad_structure(0x01, b"\x06") + ad_structure(
                0x07, bytes.fromhex(SERVICE_UUID.replace("-", ""))[::-1]
            )
            self.device.scan_response_data = ad_structure(0x09, self.args.name.encode())
            self.device.advertising_interval_min = ADVERTISING_INTERVAL_MS
            self.device.advertising_interval_max = ADVERTISING_INTERVAL_MS
            self.device.on("connection", self._on_connection)
            await self.device.power_on()

            log.info("powered on: gear %d of %d, battery %d %%", self.gear, self.gear_count, self.battery_percent)
            await self.press_pairing_button()
            if self.args.auto:
                self.toggle_auto()
            print(__doc__[__doc__.index("Keys:"):])

            interactive = sys.stdin.isatty()
            if interactive:
                saved = termios.tcgetattr(sys.stdin)
                tty.setcbreak(sys.stdin)
                asyncio.get_running_loop().add_reader(sys.stdin, self.on_keys)
            try:
                await self.quit.wait()
            finally:
                if interactive:
                    asyncio.get_running_loop().remove_reader(sys.stdin)
                    termios.tcsetattr(sys.stdin, termios.TCSADRAIN, saved)
        finally:
            await transport.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Fake Pinion Smart.Shift gearbox")
    parser.add_argument("--hci", type=int, help="adapter index, e.g. 1 for hci1 (default: the first USB adapter)")
    parser.add_argument("--transport", help="full Bumble transport spec, instead of --hci")
    parser.add_argument("--address", default="F4:50:49:4E:00:01", help="static random address to advertise from")
    parser.add_argument("--name", default="Fake Pinion")
    parser.add_argument("--gear", type=int, default=5, help="starting gear")
    parser.add_argument("--gears", type=int, default=12, help="number of gears (12, 9 or 6)")
    parser.add_argument("--battery", type=int, default=87, help="starting battery level in percent")
    parser.add_argument("--serial", type=lambda s: int(s, 0), default=12345678)
    parser.add_argument("--auto", action="store_true", help="start with the automatic gear sweep on")
    parser.add_argument("--auto-interval", type=float, default=3.0, help="seconds between automatic shifts")
    parser.add_argument("--always-advertise", action="store_true",
                        help="advertise whenever nobody is connected (the real gearbox does not)")
    parser.add_argument("-v", "--verbose", action="store_true", help="include Bumble's own log")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s", datefmt="%H:%M:%S")
    logging.getLogger("bumble").setLevel(logging.INFO if args.verbose else logging.WARNING)
    try:
        asyncio.run(FakeGearbox(args).run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
