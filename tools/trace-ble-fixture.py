#!/usr/bin/python3
"""BlueZ NUS test peripheral for the optional PocketMonitor TRACE GATT test."""
import struct

import dbus
import dbus.service
import dbus.mainloop.glib
from gi.repository import GLib

dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
bus = dbus.SystemBus()
BLUEZ = 'org.bluez'
PROPS = 'org.freedesktop.DBus.Properties'
GATT = 'org.bluez.Gatt'
AD = 'org.bluez.LEAdvertisement1'
UART = '6e40000{}-b5a3-f393-e0a9-e50e24dcca9e'
ROOT = '/pocketmonitor_trace'
SERVICE, WRITE, NOTIFY, ADV = [ROOT + part for part in ('/service', '/service/write', '/service/notify', '/adv')]
ADAPTER = '/org/bluez/hci0'
IDENTITY = bytes.fromhex('3a' * 32)
trace_count = 0


class Obj(dbus.service.Object):
    def __init__(self, obj_path, interface, props):
        super().__init__(bus, obj_path)
        self.interface, self.props = interface, props
    @dbus.service.method(PROPS, in_signature='s', out_signature='a{sv}')
    def GetAll(self, interface):
        return self.props if interface == self.interface else {}
    @dbus.service.signal(PROPS, signature='sa{sv}as')
    def PropertiesChanged(self, interface, changed, invalidated):
        pass


class App(dbus.service.Object):
    @dbus.service.method('org.freedesktop.DBus.ObjectManager', out_signature='a{oa{sa{sv}}}')
    def GetManagedObjects(self):
        return {obj.__dbus_object_path__: {obj.interface: obj.props} for obj in (service, write, notify)}


class Advertising(Obj):
    @dbus.service.method(AD)
    def Release(self):
        pass


class Tx(Obj):
    enabled = False
    @dbus.service.method(GATT+'Characteristic1')
    def StartNotify(self):
        self.enabled = True
        print('StartNotify', flush=True)
    @dbus.service.method(GATT+'Characteristic1')
    def StopNotify(self):
        self.enabled = False
        print('StopNotify', flush=True)
    def send(self, raw):
        if self.enabled:
            self.PropertiesChanged(self.interface, {'Value':dbus.ByteArray(raw)}, [])


class Rx(Obj):
    @dbus.service.method(GATT+'Characteristic1', in_signature='aya{sv}')
    def WriteValue(self, raw, options):
        global trace_count
        frame = bytes(raw)
        code = frame[0]
        if code == 1:
            notify.send(bytes([5, 2, 18, 22]) + IDENTITY + bytes(12) +
                        struct.pack('<IIBB', 869525, 62500, 7, 5) + b'Trace Test')
        elif code == 0x16:
            notify.send(bytes([13, 14, 8, 0]) + bytes(56) + b'v1.fixture'.ljust(20, b'\0') + bytes(2))
        elif code == 4:
            notify.send(bytes([2, 0, 0, 0, 0]))
            GLib.timeout_add(50, lambda: (notify.send(bytes([4, 0, 0, 0, 0])), False)[1])
        elif code == 0x0a:
            notify.send(bytes([0x0a]))
        elif code in (0x14, 0x38):
            notify.send(bytes([0x0f]))
        elif code == 0x28:
            notify.send(bytes([0x15]))
        elif code == 0x40:
            notify.send(bytes([0x1c]))
        elif code == 36:
            trace_count += 1
            print(f'TRACE request {trace_count}', flush=True)
            notify.send(bytes([6, 0]) + frame[1:5] + struct.pack('<I', 3000))
            if trace_count == 1:
                response = bytes([0x89, 0, 6, frame[9]]) + frame[1:9] + frame[10:] + bytes([248, 12, 236, 16])
                GLib.timeout_add(100, lambda: (notify.send(response), False)[1])
            elif trace_count == 2:
                device = options['device']
                def disconnect():
                    print('Dropping GATT connection', flush=True)
                    peer = dbus.Interface(bus.get_object(BLUEZ, device), 'org.bluez.Device1')
                    peer.Disconnect(reply_handler=lambda: None, error_handler=failure)
                    return False
                GLib.timeout_add(400, disconnect)
        else:
            notify.send(bytes([1, 1]))


app = App(bus, ROOT)
service = Obj(SERVICE, GATT + 'Service1', {'UUID': UART.format(1), 'Primary': dbus.Boolean(True)})
write = Rx(WRITE, GATT + 'Characteristic1', {
    'UUID': UART.format(2), 'Service': dbus.ObjectPath(SERVICE), 'Flags': dbus.Array(['write'], signature='s'),
})
notify = Tx(NOTIFY, GATT + 'Characteristic1', {
    'UUID': UART.format(3), 'Service': dbus.ObjectPath(SERVICE), 'Flags': dbus.Array(['notify'], signature='s'),
})
adv = Advertising(ADV, AD, {
    'Type': 'peripheral', 'ServiceUUIDs': dbus.Array([UART.format(1)], signature='s'), 'LocalName': 'Trace Test',
})
adapter = bus.get_object(BLUEZ, ADAPTER)
loop = GLib.MainLoop()


def failure(error):
    print(error, flush=True)
    loop.quit()


def ready():
    print('Advertising', flush=True)


dbus.Interface(adapter, GATT + 'Manager1').RegisterApplication(
    ROOT, {}, reply_handler=lambda: print('GATT registered', flush=True), error_handler=failure,
)
dbus.Interface(adapter, 'org.bluez.LEAdvertisingManager1').RegisterAdvertisement(
    ADV, {}, reply_handler=ready, error_handler=failure,
)
loop.run()
