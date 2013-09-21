@(id: String)(implicit r: RequestHeader)

$(function() {

  var WS = window['MozWebSocket'] ? MozWebSocket : WebSocket;
  var wsSocket = new WS("@routes.Application.websocket(id).webSocketURL()");

  var sendMessage = function() {
    wsSocket.send(JSON.stringify(
      { msg: $("#msg").val() }
    ))
    $("#msg").val('');
  }

  var receiveEvent = function(event) {
    console.log(event);
    var data = JSON.parse(event.data);
    // Handle errors
    if(data.error) {
      console.log("WS Error ", data.error);
      wsSocket.close();
      // TODO manage error
      return;
    } else {
      console.log("WS received ", data);
      // TODO manage display
    }

  }

  var handleReturnKey = function(e) {
    if(e.charCode == 13 || e.keyCode == 13) {
      e.preventDefault();
      sendMessage();
    }
  }

  $("#msg").keypress(handleReturnKey);

  wsSocket.onmessage = receiveEvent;

})