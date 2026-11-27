"""Independent RTMP receiver for byte-stream regression testing on socket-restricted hosts."""
import os, struct, subprocess as sp
from pathlib import Path
from build import root,out,java,gradle

def receive(fixture, dest):
    pub=sp.Popen([str(java),"-cp",str(out/"check.jar")+os.pathsep+str(gradle/"lib/kotlin-stdlib-2.0.21.jar"),"tw.meikou.live.PublisherCheckKt","pipe",str(fixture)],stdin=sp.PIPE,stdout=sp.PIPE)
    rd,wr=pub.stdout,pub.stdin
    def read(n):
        b=rd.read(n)
        if len(b)!=n:raise EOFError
        return b
    def send(b):wr.write(b);wr.flush()
    def u24(v):return v.to_bytes(3,"big")
    def string(s):return b'\x02'+struct.pack(">H",len(s))+s.encode()
    def number(n):return b'\0'+struct.pack(">d",n)
    def reply(payload,sid=0):
        head=b'\x03'+bytes(3)+u24(len(payload))+b'\x14'+struct.pack("<I",sid)
        send(head+payload[:128]+b''.join(b'\xc3'+payload[i:i+128] for i in range(128,len(payload),128)))
    try:
        assert read(1)==b'\x03'
        c1=read(1536);s1=b'\0'*1536;send(b'\x03'+s1+c1);assert read(1536)==s1
        with open(dest,"wb") as f:
            f.write(b'FLV\x01\x05\0\0\0\x09'+bytes(4))
            while True:
                first=rd.read(1)
                if not first:break
                channel=first[0];assert channel<64
                time=int.from_bytes(read(3),"big");length=int.from_bytes(read(3),"big");kind=read(1)[0];sid=int.from_bytes(read(4),"little")
                payload=bytearray(read(min(length,128)))
                while len(payload)<length:
                    assert read(1)==bytes([0xc0|channel]);payload.extend(read(min(128,length-len(payload))))
                if kind==20:
                    name=payload[3:3+int.from_bytes(payload[1:3],"big")].decode()
                    if name=="connect":reply(string("_result")+number(1)+b'\x05\x05')
                    elif name=="createStream":reply(string("_result")+number(2)+b'\x05'+number(1))
                    elif name=="publish":
                        for code in ("NetStream.Publish.Notify", "NetStream.Publish.Start"):
                            reply(string("onStatus")+number(0)+b'\x05\x03\0\x04code'+string(code)+b'\0\0\x09',1)
                    else:raise ValueError(name)
                else:
                    assert kind in (8,9) and sid==1
                    f.write(bytes([kind])+u24(length)+u24(time)+bytes(4)+payload+struct.pack(">I",length+11))
        assert pub.wait(5)==0
    finally:
        if pub.poll() is None:pub.kill()
        pub.wait()

