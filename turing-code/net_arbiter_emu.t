% Net Arbiter Emulator
% Emulates the Java Net Arbiter

const ARBITER_PORT : int := 7007
var arbiterStream : int
var clientAddress : string

put "Waiting for connection on ", ARBITER_PORT

arbiterStream := Net.WaitForConnection(ARBITER_PORT, clientAddress)
if arbiterStream < 0 then
    put "Error in listening to arbiter port! (code ", Error.Last, ")"
    put Error.LastMsg
    
    return
end if

put "Connection established with ", clientAddress
put : arbiterStream, "hello"

loop
    /*var input : string
    get : arbiterStream, input : *
    put "Echo: ", input
    exit when index(input, "bai") not= 0*/
    
    var nBytes := Net.BytesAvailable(arbiterStream)
    
    put "Can read: chr ", Net.CharAvailable(arbiterStream), ", lne ", Net.LineAvailable(arbiterStream), ", tok ", Net.TokenAvailable(arbiterStream)
    put "Data check: ", nBytes
    
    if nBytes > 0 then
        var input : string
        get : arbiterStream, input : *
        
        put input
    end if
    
    delay(1000)
end loop

Net.CloseConnection(arbiterStream)
put "Disconnected with ", clientAddress