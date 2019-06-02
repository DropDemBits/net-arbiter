% Arbiter Test
% Tests the functions of the arbiter
import NetArbiter in "net_arbiter.t"


%%% Utilities %%%
proc cleanUp (var arbiter : ^ Arbiter)
    put "Stopping arbiter"
    arbiter -> shutdown ()
    free arbiter
end cleanUp


%%% Main Code %%%
proc EndpointTest ()
    var connID : int4 := 0
    var arb : ^ Arbiter
    
    new Arbiter, arb
    
    put "Starting arbiter"
    arb -> startup (7007, 0)
    if arb -> getError () not= 0 then
        put "Error occured (", NetArbiter.errorToString (arb -> getError ()), ")"
        cleanUp (arb)
        return
    end if
    
    %% Connect to the remote arbiter %%
    put "Connecting to a remote arbiter"
    connID := arb -> connectTo ("localhost", 8087)
    
    if connID >= 0 then
        put "Connected to remote arbiter #", connID
    else
        put "Error occured (", NetArbiter.errorToString (arb -> getError ()), ")"
        arb -> shutdown ()
        return
    end if
    
    %% Send the test packet data %%
    const TEST_STRING : string := "hello, networking world!"
    var len : int := 0
    
    put "Sending test data: ", TEST_STRING
    var data : array 1 .. length (TEST_STRING) of nat1
    for i : 1 .. length (TEST_STRING)
        data (i) := ord (TEST_STRING (i))
    end for
    
    Input.Flush ()
    loop
        exit when Input.hasch ()
        len := arb -> writePacket (connID, data)
        
        %% Receive the echo'd data %%
        var recvStart : int := Time.Elapsed
        loop
            exit when arb -> poll()
        end loop
        put "Sent -> Recv: ", Time.Elapsed - recvStart
        
        loop
            var recentPacket : ^Packet := arb -> getPacket ()
            exit when not arb -> nextPacket ()
            put "Oops, more packets!"
        end loop
    end loop
    Input.Flush ()
    
    %% Disconnect %%
    put "Disconnecting remote arbiter #", connID
    arb -> disconnect (connID)
    
    cleanUp (arb)
end EndpointTest


EndpointTest ()