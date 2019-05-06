% Net Arbiter Test
% Test the functions of the Net Arbiter

const ARBITER_PORT : int := 7007
const EXIT := 'arb:X'
const CONNECT := 'arb:C'

const ESTABLISHED := 'arb:E'
const NEW_CONNECT := 'arb:N'

var conSend : string := ""
var netSock : int := -1

fcn toNetInt ( num : nat4, bytes : nat1 ) : string
    const dictionary : string := "0123456789abcdef"
    var out : string := ""
    
    % Pre-condition checking
    assert bytes >= 1 and bytes <= 4
    
    if bytes not= 4 then
        assert num <= (1 shl (bytes * 8)) - 1
    end if
    
    % Convert to network string
    for decreasing nib : (bytes * 2) - 1 .. 0
        out += dictionary (((num shr (nib * 4)) & 16#F) + 1)
    end for
        
    result out
end toNetInt

% Parse integer from the data
fcn parseInt ( data : string, bytes : nat1 ) : nat4
    var num : nat4 := 0

    for i : 1 .. length(data)
        num *= 16
    
        if data (i) >= '0' and data (i) <= '9' then
            num += data (i) - '0'
        elsif data (i) >= 'a' and data (i) <= 'b'
            num += (data (i) - 'a') + 10
        end if
    end for
    
    result num
end parseInt

fcn readPacket () : nat4
    % Check if there's actually any data
    if Net.BytesAvailable(netSock) <= 0 then
        % No data to read, silently return
        result cheat(nat4, -1)
    end if

    % Check packet id
    var packet_id : string
    read : netSock, packet_id : 4
    
    if packet_id = "arb:" then
        var command_id : char
        var data : string
        
        % Get command id
        read : netSock, command_id : 1
        
        % Since most of the received packets have a connection id,
        % read the connection id
        read : netSock, data : 4
        
        case command_id of
        label 'E':
            put "Established remote connection at #", data
        label 'N':
            put "New connection at #", data
        label 'R':
            put "Remote connection closed on #", data
        end case
        
        result parseInt(data)
    end if
    
    result cheat(nat4, -1)
end readPacket

put "Opening connection with arbiter"

% Connect to Arbiter
netSock := Net.OpenConnectionBinary("localhost", ARBITER_PORT)
if netSock < 0 then
    put "Error with opening connection (", Error.Last, ")"
    put Error.LastMsg
    quit
end if

put "Connection opened with arbiter"

% Connect to remote
put "Sending opening connection"
var port : nat4 := 6886
var address : string := "localhost"

conSend := ""
conSend += CONNECT
conSend += toNetInt(port, 2)
conSend += toNetInt(length(address), 1)
conSend += address
write : netSock, conSend : length(conSend)

% Acquire Connection ID

loop
    exit when Net.BytesAvailable(netSock) > 0
end loop

put Net.BytesAvailable(netSock)
readPacket()

Input.Pause()

% Send a few data bits to remote
% Disconnect from remote

put "Sending connection exit"

conSend := ""
conSend += EXIT
write : netSock, conSend : length(conSend)

put "Closing connection"
Net.CloseConnection(netSock)