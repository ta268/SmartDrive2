#!/usr/bin/env python3
import sys
import time
import random
import threading
import collections
from datetime import datetime

import dbus
import dbus.service
import dbus.mainloop.glib
from gi.repository import GLib

# DBus Interfaces
DBUS_OM_IFACE = 'org.freedesktop.DBus.ObjectManager'
DBUS_PROP_IFACE = 'org.freedesktop.DBus.Properties'
GATT_MANAGER_IFACE = 'org.bluez.GattManager1'
GATT_SERVICE_IFACE = 'org.bluez.GattService1'
GATT_CHRC_IFACE = 'org.bluez.GattCharacteristic1'
LE_ADVERTISING_MANAGER_IFACE = 'org.bluez.LEAdvertisingManager1'
LE_ADVERTISEMENT_IFACE = 'org.bluez.LEAdvertisement1'

BLUEZ_SERVICE_NAME = 'org.bluez'

# Custom UUIDs matching the Android Java side
SERVICE_UUID = '0000ffe0-0000-1000-8000-00805f9b34fb'
# TX Characteristic: Raspberry Pi → Android (Notify)
CHAR_UUID = '0000ffe1-0000-1000-8000-00805f9b34fb'
# RX Characteristic: Android → Raspberry Pi (Write) 「6E400002」
RX_CHAR_UUID = '6e400002-b5a3-f393-e0a9-e50e24dcca9e'


class InvalidArgsException(dbus.exceptions.DBusException):
    _dbus_error_name = 'org.bluez.Error.InvalidArguments'


class NotSupportedException(dbus.exceptions.DBusException):
    _dbus_error_name = 'org.bluez.Error.NotSupported'


class Advertisement(dbus.service.Object):
    """
    BlueZ LE Advertisement object.
    Exposes properties so that BlueZ can register our BLE advertisement.
    """
    def __init__(self, bus, index):
        self.path = f'/org/bluez/example/advertisement{index}'
        super().__init__(bus, self.path)
        self.properties = {
            LE_ADVERTISEMENT_IFACE: {
                'Type': 'peripheral',
                'ServiceUUIDs': dbus.Array([SERVICE_UUID], signature='s'),
                'LocalName': 'omnibus185',
                'Discoverable': dbus.Boolean(True),
                'IncludeTxPower': dbus.Boolean(True)
            }
        }

    def get_path(self):
        return dbus.ObjectPath(self.path)

    @dbus.service.method(DBUS_PROP_IFACE, in_signature='ss', out_signature='v')
    def Get(self, interface, prop):
        if interface in self.properties and prop in self.properties[interface]:
            return self.properties[interface][prop]
        raise InvalidArgsException()

    @dbus.service.method(DBUS_PROP_IFACE, in_signature='s', out_signature='a{sv}')
    def GetAll(self, interface):
        if interface in self.properties:
            return self.properties[interface]
        raise InvalidArgsException()

    @dbus.service.method(LE_ADVERTISEMENT_IFACE, in_signature='', out_signature='')
    def Release(self):
        print("Advertisement released by BlueZ")


class Application(dbus.service.Object):
    """
    BlueZ GATT Application object.
    Acts as the ObjectManager exposing our GATT Services and Characteristics.
    """
    def __init__(self, bus):
        super().__init__(bus, '/')
        self.services = []

    def add_service(self, service):
        self.services.append(service)

    @dbus.service.method(DBUS_OM_IFACE, in_signature='', out_signature='a{oa{sa{sv}}}')
    def GetManagedObjects(self):
        objects = {}
        for service in self.services:
            objects[service.get_path()] = service.get_properties()
            for chrc in service.get_characteristics():
                objects[chrc.get_path()] = chrc.get_properties()
        return objects


class SmartDriveService(dbus.service.Object):
    """
    GATT Service exposing the SmartDrive features.
    """
    def __init__(self, bus, index):
        self.path = f'/org/bluez/example/service{index}'
        super().__init__(bus, self.path)
        self.characteristics = []
        self.properties = {
            GATT_SERVICE_IFACE: {
                'UUID': SERVICE_UUID,
                'Primary': dbus.Boolean(True),
                'Characteristics': dbus.Array([], signature='o')
            }
        }

    def get_path(self):
        return dbus.ObjectPath(self.path)

    def get_properties(self):
        return {GATT_SERVICE_IFACE: self.properties[GATT_SERVICE_IFACE]}

    def add_characteristic(self, characteristic):
        self.characteristics.append(characteristic)
        self.properties[GATT_SERVICE_IFACE]['Characteristics'].append(characteristic.get_path())

    def get_characteristics(self):
        return self.characteristics

    @dbus.service.method(DBUS_PROP_IFACE, in_signature='ss', out_signature='v')
    def Get(self, interface, prop):
        if interface in self.properties and prop in self.properties[interface]:
            return self.properties[interface][prop]
        raise InvalidArgsException()

    @dbus.service.method(DBUS_PROP_IFACE, in_signature='s', out_signature='a{sv}')
    def GetAll(self, interface):
        if interface in self.properties:
            return self.properties[interface]
        raise InvalidArgsException()


class SmartDriveCharacteristic(dbus.service.Object):
    """
    GATT Characteristic that handles notifications, data queueing, and retransmissions.
    """
    def __init__(self, bus, index, service):
        self.path = f'{service.path}/char{index}'
        super().__init__(bus, self.path)
        self.service = service
        self.notifying = False
        self.value = []
        self.queue = collections.deque(maxlen=1000) # Prevents memory leaks if queue becomes too long
        self.lock = threading.Lock()

        self.properties = {
            GATT_CHRC_IFACE: {
                'Service': service.get_path(),
                'UUID': CHAR_UUID,
                'Flags': dbus.Array(['read', 'notify'], signature='s'),
                'Notifying': dbus.Boolean(False),
                'Value': dbus.Array([], signature='y')
            }
        }

    def get_path(self):
        return dbus.ObjectPath(self.path)

    def get_properties(self):
        return {GATT_CHRC_IFACE: self.properties[GATT_CHRC_IFACE]}

    @dbus.service.method(DBUS_PROP_IFACE, in_signature='ss', out_signature='v')
    def Get(self, interface, prop):
        if interface in self.properties and prop in self.properties[interface]:
            return self.properties[interface][prop]
        raise InvalidArgsException()

    @dbus.service.method(DBUS_PROP_IFACE, in_signature='s', out_signature='a{sv}')
    def GetAll(self, interface):
        if interface in self.properties:
            return self.properties[interface]
        raise InvalidArgsException()

    @dbus.service.method(GATT_CHRC_IFACE, in_signature='a{sv}', out_signature='ay')
    def ReadValue(self, options):
        print("ReadValue called")
        return self.properties[GATT_CHRC_IFACE]['Value']

    @dbus.service.method(GATT_CHRC_IFACE, in_signature='', out_signature='')
    def StartNotify(self):
        if self.notifying:
            print("Already notifying, skipping StartNotify")
            return
        
        print("StartNotify called (Android notification subscription enabled)")
        self.notifying = True
        self.properties[GATT_CHRC_IFACE]['Notifying'] = dbus.Boolean(True)
        self.PropertiesChanged(GATT_CHRC_IFACE, {'Notifying': dbus.Boolean(True)}, [])
        # キューのフラッシュは READY 信号受信時に行うため、ここでは開始しない

    @dbus.service.method(GATT_CHRC_IFACE, in_signature='', out_signature='')
    def StopNotify(self):
        if not self.notifying:
            print("Not notifying, skipping StopNotify")
            return
        
        print("StopNotify called (Android notification subscription disabled/disconnected)")
        self.notifying = False
        self.properties[GATT_CHRC_IFACE]['Notifying'] = dbus.Boolean(False)
        self.PropertiesChanged(GATT_CHRC_IFACE, {'Notifying': dbus.Boolean(False)}, [])

    @dbus.service.signal(DBUS_PROP_IFACE, signature='sa{sv}as')
    def PropertiesChanged(self, interface, changed_properties, invalidated_properties):
        pass

    def send_data(self, data_str):
        """
        Processes new data.
        Sends immediately if connected and notifying. Otherwise, queues the data.
        """
        with self.lock:
            if self.notifying:
                # If there are items in the queue, we shouldn't send real-time data first
                # to preserve chronological order. We append it to the queue and flush the queue instead.
                if len(self.queue) > 0:
                    self.queue.append(data_str)
                    # flush_queue runs asynchronously, so let's trigger it.
                    threading.Thread(target=self.flush_queue, daemon=True).start()
                else:
                    self._notify_raw(data_str)
            else:
                self.queue.append(data_str)
                print(f"Disconnected. Queued data (Current size: {len(self.queue)}): {data_str.strip()}")

    def _notify_raw(self, data_str):
        """Helper to transmit a single string notification"""
        try:
            print(f"Transmitting notification: {data_str.strip()}")
            value_bytes = data_str.encode('utf-8')
            dbus_val = dbus.Array([dbus.Byte(b) for b in value_bytes], signature='y')
            self.properties[GATT_CHRC_IFACE]['Value'] = dbus_val
            self.PropertiesChanged(GATT_CHRC_IFACE, {'Value': dbus_val}, [])
        except Exception as e:
            print(f"Failed to send notification: {e}")

    def flush_queue(self):
        """Flushes the queued messages chronologically (FIFO) with a short delay"""
        # Ensure only one thread flushes at a time
        with self.lock:
            if not self.notifying or len(self.queue) == 0:
                return

            print(f"Reconnected. Flushing {len(self.queue)} queued records...")
            while len(self.queue) > 0 and self.notifying:
                data_str = self.queue.popleft()
                self._notify_raw(data_str)
                # Sleep briefly to ensure Android app has time to process without packet drops
                time.sleep(0.05)
            print("Queue flush operation completed.")


class RxCharacteristic(dbus.service.Object):
    """
    RX Characteristic (UUID: 6E400002-B5A3-F393-E0A9-E50E24DCCA9E)
    Android からの書き込みを受け取るキャラクタリスティック。
    "READY" を受信したたときにスマートドライブ・チャラクタリスティックのキューフラッシュを開始する。
    """
    def __init__(self, bus, index, service, tx_characteristic):
        self.path = f'{service.path}/char{index}'
        super().__init__(bus, self.path)
        self.service = service
        self.tx_char = tx_characteristic  # フラッシュ対象の TX キャラクタリスティック

        self.properties = {
            GATT_CHRC_IFACE: {
                'Service': service.get_path(),
                'UUID': RX_CHAR_UUID,
                'Flags': dbus.Array(['write', 'write-without-response'], signature='s'),
                'Value': dbus.Array([], signature='y')
            }
        }

    def get_path(self):
        return dbus.ObjectPath(self.path)

    def get_properties(self):
        return {GATT_CHRC_IFACE: self.properties[GATT_CHRC_IFACE]}

    @dbus.service.method(DBUS_PROP_IFACE, in_signature='ss', out_signature='v')
    def Get(self, interface, prop):
        if interface in self.properties and prop in self.properties[interface]:
            return self.properties[interface][prop]
        raise InvalidArgsException()

    @dbus.service.method(DBUS_PROP_IFACE, in_signature='s', out_signature='a{sv}')
    def GetAll(self, interface):
        if interface in self.properties:
            return self.properties[interface]
        raise InvalidArgsException()

    @dbus.service.method(GATT_CHRC_IFACE, in_signature='aya{sv}', out_signature='')
    def WriteValue(self, value, options):
        """
        Android 側からの書き込みを処理する。
        "READY" を受信した場合、キューに溜まった蹟積データの一括送信を開始する。
        """
        received = bytes(value).decode('utf-8', errors='ignore').strip()
        print(f"RX WriteValue received from Android: '{received}'")

        if received == 'READY':
            print("READY signal detected. Triggering queue flush...")
            threading.Thread(target=self.tx_char.flush_queue, daemon=True).start()
        else:
            print(f"Unknown write value: {received}")


def get_dbus_interface(bus, service, path, interface):
    obj = bus.get_object(service, path)
    return dbus.Interface(obj, interface)


def register_app(bus, app):
    gatt_mgr = get_dbus_interface(bus, BLUEZ_SERVICE_NAME, '/org/bluez/hci0', GATT_MANAGER_IFACE)
    gatt_mgr.RegisterApplication(app.get_path(), {},
                                 reply_handler=lambda: print("GATT Application registered successfully"),
                                 error_handler=lambda e: print(f"GATT Application registration failed: {e}"))


def register_ad(bus, ad):
    ad_mgr = get_dbus_interface(bus, BLUEZ_SERVICE_NAME, '/org/bluez/hci0', LE_ADVERTISING_MANAGER_IFACE)
    ad_mgr.RegisterAdvertisement(ad.get_path(), {},
                                 reply_handler=lambda: print("Advertisement registered successfully"),
                                 error_handler=lambda e: print(f"Advertisement registration failed: {e}"))


def data_generator_loop(characteristic):
    """
    Simulates real-time sensor measurements.
    Generates standard X,Y,Z values every second and injects dangerous events periodically.
    """
    event_counter = 0
    dangerous_events = ["s_braked", "s_accelerated", "s_steered", "waved", "unstable_speed"]

    while True:
        time.sleep(1.0)
        timestamp = datetime.now().strftime("%Y/%m/%d-%H:%M:%S")
        
        # Simulating active accelerometer values
        x = round(random.uniform(-1.0, 1.0), 3)
        y = round(random.uniform(-1.0, 1.0), 3)
        z = round(random.uniform(9.5, 10.1), 3)

        # Trigger simulated events
        event_counter += 1
        active_event = None
        if event_counter >= 15:
            # Trigger a random dangerous driving event every 15 seconds
            active_event = random.choice(dangerous_events)
            event_counter = 0

        # Construct JSON matching Android expectations
        json_parts = [
            f'"x": {x}',
            f'"y": {y}',
            f'"z": {z}',
            f'"timestamp": "{timestamp}"'
        ]
        
        for event in dangerous_events:
            val = "true" if event == active_event else "false"
            json_parts.append(f'"{event}": {val}')

        json_str = "{" + ", ".join(json_parts) + "}\n"
        characteristic.send_data(json_str)


def main():
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()

    # Create GATT objects
    app = Application(bus)
    service = SmartDriveService(bus, 0)
    # TX Characteristic: Pi → Android (Notify)
    tx_characteristic = SmartDriveCharacteristic(bus, 0, service)
    # RX Characteristic: Android → Pi (Write: READY信号受信用)
    rx_characteristic = RxCharacteristic(bus, 1, service, tx_characteristic)

    service.add_characteristic(tx_characteristic)
    service.add_characteristic(rx_characteristic)
    app.add_service(service)

    # Create Advertisement object
    ad = Advertisement(bus, 0)

    # Register GATT application and advertising
    register_app(bus, app)
    register_ad(bus, ad)

    # Start data generation thread
    generator_thread = threading.Thread(target=data_generator_loop, args=(tx_characteristic,), daemon=True)
    generator_thread.start()

    # Run the GLib MainLoop
    print("BLE SmartDrive GATT Server is running. Press Ctrl+C to stop.")
    mainloop = GLib.MainLoop()
    try:
        mainloop.run()
    except KeyboardInterrupt:
        print("\nStopping BLE GATT Server...")
        mainloop.quit()


if __name__ == '__main__':
    main()
