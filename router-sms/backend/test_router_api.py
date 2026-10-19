import json
import threading
import unittest
from http.client import HTTPConnection
from unittest.mock import patch
import router_api as api

class SecurityTests(unittest.TestCase):
    def setUp(self):
        api.CONFIG = dict(modem='0', token='a'*64, lan_cidr='127.0.0.0/8', enable_voice=False)
    def test_number_injection(self):
        for value in ['--help', '123;reboot', "123,text='x'", '+123$(id)', None]:
            with self.assertRaises(api.ApiError): api.number(value)
        self.assertEqual(api.number('+8613812345678'), '+8613812345678')
    def test_text_quoting(self):
        for value in ["中文,number=999;$(id)", "it's fine", 'He said "hi"', 'line\nnext']:
            quoted = api.properties_text(value)
            self.assertEqual(quoted[1:-1], value)
            self.assertNotIn(quoted[0], value)
        for value in ['"both\' quotes', 'a\\b', '\x00', '', 'a'*1601]:
            with self.assertRaises(api.ApiError): api.properties_text(value)
    def test_no_shell(self):
        with patch('router_api.subprocess.run') as run:
            run.return_value.returncode=0; run.return_value.stdout='{}'
            api.mm('-m','0',structured=True)
            self.assertIsInstance(run.call_args.args[0], list)
            self.assertNotIn('shell', run.call_args.kwargs)
            self.assertEqual(run.call_args.kwargs['timeout'],20)
    def test_voice_fail_closed(self):
        with patch('router_api.mm',side_effect=api.ApiError(502,'No Voice interface')):
            self.assertFalse(api.voice()['controls_enabled'])
        with patch('router_api.mm',return_value={'modem':{'voice':{'calls':[]}}}):
            self.assertTrue(api.voice()['interface_available'])
            self.assertFalse(api.voice()['controls_enabled'])
            with self.assertRaises(api.ApiError): api.dispatch('POST','/v1/calls/dial',{'number':'12345'})
    def test_call_ownership(self):
        api.CONFIG['enable_voice']=True
        with patch('router_api.mm',return_value={'calls':[]}):
            with self.assertRaises(api.ApiError) as e: api.dispatch('POST','/v1/calls/hangup',{'id':'9'})
            self.assertEqual(e.exception.status,404)
    def test_sms_send_is_separate_argv(self):
        path=api.PREFIX+'SMS/9'
        with patch('router_api.mm',side_effect=["Successfully created new SMS: "+path,'sent']) as mm:
            out=api.dispatch('POST','/v1/sms/send',{'number':'12345','text':'中文,number=999;$(id)'})
            self.assertEqual(out['state'],'submitted')
            self.assertEqual(mm.call_args_list[1].args,('-s',path,'--send'))
    def test_pagination(self):
        ids=[api.PREFIX+'SMS/'+str(i) for i in range(26)]
        with patch('router_api.listed',return_value=ids), patch('router_api.mm',return_value={}):
            self.assertEqual(api.dispatch('GET','/v1/sms',{})['next'],25)
            self.assertEqual(len(api.dispatch('GET','/v1/sms',{'offset':25})['messages']),1)
    def test_http_auth_and_origin(self):
        server=api.HTTPServer(('127.0.0.1',0),api.Handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        def request(headers):
            c=HTTPConnection('127.0.0.1',server.server_port,timeout=3)
            c.request('GET','/v1/status',headers=headers)
            r=c.getresponse(); result=(r.status,json.loads(r.read()));c.close();return result
        try:
            with patch('router_api.dispatch',return_value={'ok':True}) as dispatch:
                self.assertEqual(request({})[0],401); dispatch.assert_not_called()
                self.assertEqual(request({'Authorization':'Bearer '+'a'*64,'Origin':'http://evil'})[0],403)
                self.assertEqual(request({'Authorization':'Bearer '+'a'*64})[0],200)
                api.CONFIG['lan_cidr']='192.168.8.0/24'
                self.assertEqual(request({'Authorization':'Bearer '+'a'*64})[0],403)
        finally: server.shutdown();server.server_close();thread.join()

if __name__=='__main__': unittest.main()
