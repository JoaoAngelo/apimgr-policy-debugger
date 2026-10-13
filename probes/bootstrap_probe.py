import sys, time
from java.util import Properties
from com.vordel.es import EntityStoreFactory
from com.vordel.es.util import ShorthandKeyFinder
from com.vordel.common.crypto import PasswordCipher
from com.vordel.precipitate import ESSolutionPack
from com.vordel.dwe import ServiceCircuitCache

url = sys.argv[1]
t0 = time.time()
store = EntityStoreFactory.getInstance().getEntityStoreForURL(url)
store.connect(url, Properties())
t1 = time.time()
from java.lang import String as JString
pack = ESSolutionPack(store, PasswordCipher(JString("").toCharArray()), ServiceCircuitCache())
t2 = time.time()
print "store %.2fs pack %.2fs" % (t1 - t0, t2 - t1)

skf = ShorthandKeyFinder(store)
mods = {}
for e in skf.getEntities("/[NetService]name=Service/[LoadableModule]") + skf.getEntities("/[LoadableModule]"):
    t = e.getType()
    lo = t.getConstantField("loadorder")
    mods[t.getName()] = (lo.getValues()[0].getData() if lo and lo.getValues() else "-", e.getStringValue("class"))
print "modules:", len(mods)
for k in sorted(mods, key=lambda k: int(mods[k][0]) if mods[k][0] != "-" else 10**7):
    print "  %-40s order=%-8s %s" % (k, mods[k][0], mods[k][1])

circs = skf.getEntities("/[CircuitContainer]**/[FilterCircuit]")
print "policies:", len(circs)
for c in circs[:15]:
    print "  ", c.getStringValue("name")
