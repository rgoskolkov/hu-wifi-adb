# Embedded ADB client

`arm64-v8a/libadb.so` is copied from LADB 2.6, commit
`60f48029cf9d8e0bc848ca41a7bd76694d4ab796`.

SHA256: `47ea035fa5ed57f6149a2b025bbbd4b21584c355c05d0400416804715e4c12de`.

The main application uses only this native executable from LADB's approach; no
LADB UI, pairing flow, Kotlin code or AndroidX dependencies are included.
See `LICENSE` for the upstream redistribution terms.
