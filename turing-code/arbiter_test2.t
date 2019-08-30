setscreen("text")

var sock := Net.OpenConnectionBinary("127.0.0.1", 7007)

if sock < 0 then
    put "No conn"
    return
end if

var commandSeq : nat2 := 0
% Connect to
var remoteHostname : string := "localhost"
var remotePort : nat2 := 8080

fcn connectTo (hostname : string, port : nat2) : nat2
    % Packet Format:
    % length | cmdSequence | cmdID | payload (| port | len | hostname |)
    const connectSize : nat2 := (2 + 2 + 1) + 2 + 2
    var packet : array 0 .. (connectSize + length(remoteHostname) - 1) of nat1
    
    var packetLen : nat2 := upper(packet) + 1

    %% Header %%
    % Length
    packet (0) := (packetLen shr 8) & 16#FF
    packet (1) := (packetLen shr 0) & 16#FF
    % Sequence
    packet (2) := (commandSeq shr 8) & 16#FF
    packet (3) := (commandSeq shr 0) & 16#FF
    % cmdID
    packet (4) := ord ('C')
    
    %% Payload %%
    % Remote Port
    packet (5) := (port shr 8) & 16#FF
    packet (6) := (port shr 0) & 16#FF
    % Length
    packet (7) := ((length(hostname)) shr 8) & 16#FF
    packet (8) := ((length(hostname)) shr 0) & 16#FF
    % Name
    for i: 0 .. (length(remoteHostname) - 1)
        packet(9 + i) := nat1 @ (addr(hostname) + i)
    end for
    
    put "SEND IT! ", upper(packet) + 1
    write : sock, packet : upper(packet) + 1
    
    var rLen : int
    var connID : nat4

    % Response format:
    % length | cmdSequence | cmdID | payload (| src | err / connID |)
    const responseLen := (2 + 2 + 1) + 2 + 4
    var response : array 0 .. (responseLen - 1) of nat1

    loop
        rLen := Net.BytesAvailable(sock)
        exit when rLen > 0
    end loop
    
    read : sock, response : responseLen
    
    put "Pending: ", rLen

    for i : 0 .. responseLen - 1
        put intstr(response(i), 2, 16), " "..
    end for
    put ""

    nat1 @ (addr(connID) + 3) := response(7)
    nat1 @ (addr(connID) + 2) := response(8)
    nat1 @ (addr(connID) + 1) := response(9)
    nat1 @ (addr(connID) + 0) := response(10)

    put "Conn ID: ", connID
    
    commandSeq += 1
    result cheat(nat2, connID)
end connectTo

proc shutdown()
    % Length
    var packet : array 0 .. 4 of nat1
    
    packet (0) := 0
    packet (1) := 5
    % Sequence
    packet (2) := (commandSeq shr 8) & 16#FF
    packet (3) := (commandSeq shr 0) & 16#FF
    % packetID
    packet (4) := ord ('X')
    
    write : sock, packet
    
    close (sock)
end shutdown

var connID := connectTo(remoteHostname, remotePort)
shutdown()

% Close up shop

if true then return end if

% Write packet
put "Sending byte array"
var bytes : array 0 .. 10 of nat1

for i: 0 .. upper(bytes)
    bytes(i) := (upper(bytes) - i) & 16#FF
end for

write : sock, bytes %: (upper(bytes)+1)
put "Sent things, waiting for read"

% Poll
var len : int
loop
    len := Net.BytesAvailable(sock)
    exit when len > 0
end loop

% Read packet
read : sock, bytes %: len

for i: 0 .. upper(bytes)
    put intstr(bytes(i), 2, 16), " "..
    
    if i mod 16 = 15 then
        put ""
    end if
end for

% Exit Arbiter
close (sock)