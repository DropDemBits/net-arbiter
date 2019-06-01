% Arbiter Test
% Tests the functions of the arbiter
import NetArbiter in "net_arbiter.t"

var errno : int := 0
var connID : int4 := 0
var arb : ^ Arbiter

%%% Utilities %%%
proc cleanUp (arbiter : ^ Arbiter)
    put "Stopping arbiter"
    arbiter -> shutdown ()
    free arbiter
end cleanUp

%%% Main Code %%%
new Arbiter, arb

put "Starting arbiter"
arb -> startup (7007, 0)
if arb -> getError () not= 0 then
    put "Error occured (", NetArbiter.errorToString (arb -> getError ()), ")"
    cleanUp ()
    return
end if

put "Connecting to a remote arbiter"
connID := arb -> connectTo ("localhost", 8087)

if connID >= 0 then
    put "Connected to remote arbiter #", connID
else
    put "Error occured (", NetArbiter.errorToString (arb -> getError ()), ")"
    arb -> shutdown ()
    return
end if

put "Disconnecting remote arbiter #", connID
arb -> disconnect (connID)

cleanUp ()