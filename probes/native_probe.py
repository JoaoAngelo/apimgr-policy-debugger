from com.vordel.dwe import CorrelationID
from com.vordel.circuit import Message
import java.lang.System as S
print "java", S.getProperty("java.version"), S.getProperty("java.home")
cid = CorrelationID()
m = Message(cid, None)
m.put("http.request.uri", "/probe")
print "corrId", cid.toString(), "msg", m.get("http.request.uri")