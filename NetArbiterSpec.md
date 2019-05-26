## Net Arbiter Specifications
-----

netFD := OpenConnectionBinary(address : string, port : int)
netFD := WaitForConnection(port : int, var address)

# Requirements
- Must be able to accept connections (regardless of "mode")
- Must be able to passthrough packets
- Must be able to send UDP packets at a reliable rate

# Milestones
- Simple Chat Client
  - Client mode
  - Server mode
  - Packet passthrough
  
# Interface
- Control ```[command id];[payload]```
- Passthrough ```"P;[payload]"```

# Protocols
## Arbiter  - Arbiter
Transparent to endpoints

Connection:
S -> D: Connection Establish 
D -> S: Ack (Establish)
D -> ED: New Connection
S -> ES: Connection establish

Disconnect:
S -> D: Disconnect Notify
(Close connection)

Transparent passthrough of data

Ack (Establish):
```arb1```
Disconnect Notify:
```arb2```

## Endpoint - Arbiter
Exit:
```arb:X```
It is the endpoint's responsibility for closing the connection.
All remote connections will be closed by the arbiter.

Connect To:
```arb:C[port : 2][strlen : 1][address : string]```

Disconnect:
```arb:D[connID : 2]```

Listen On:
```arb:L[port : 2]```

Send Data:
```[connID : 2][payload]```

Arbiter must ignore any invalid command id's and invalid commands

## Arbiter - Endpoint
Connection Established (S -> EC)
```arb:E[connID : 2]```

New Connection (D -> ES)
```arb:N[connID : 2]```

Connection Closed by Remote
```arb:R[connID : 2]``` 

Data Receive:
```[connID : 2][size : 2][payload]```

Data Sent Successfully:
```arb:S```

Error:
```arb:W[errorCode : 2]```

### Defined Commands:

| ID (char) | Name | Payload | Description |
|-----------|------|---------|-------------|
| 0x45('C') | CONNECT | [address : lstring][port : 2] | Establishes a connection to a remote arbiter |
