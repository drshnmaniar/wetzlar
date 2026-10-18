# BLE grip module

`GripManager` provides foreground BLE scanning and a basic GATT notification session. The
embedding app owns runtime permission prompts and should request `BLUETOOTH_SCAN` and
`BLUETOOTH_CONNECT` on Android 12+, or `ACCESS_FINE_LOCATION` for scanning on Android 6–11.
The library manifest declares these permissions and marks BLE hardware optional.

Fjorden's Kiki profile identifiers and notification packet format are not defined in this
repository. Supply a verified `GripGattProfile` (service UUID, event characteristic UUID, and a
decoder) to connect. Until that reverse-engineering is completed, notification bytes are surfaced
as `GripEvent.RawNotification`; the module does not claim protocol compatibility with a specific
grip firmware. Connections are foreground only and should be closed with the camera owner.
