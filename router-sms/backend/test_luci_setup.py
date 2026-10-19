import unittest
from unittest.mock import patch
import router_api as api
import router_setup as setup

class LuCITests(unittest.TestCase):
    def setUp(self): api.CONFIG = dict(modem='0', enable_voice=True)
    def test_delete_requires_modem_ownership(self):
        with patch('router_api.listed', return_value=[]), patch('router_api.mm') as mm:
            with self.assertRaises(api.ApiError) as error: api.dispatch('POST','/v1/sms/delete',{'id':'12'})
            self.assertEqual(error.exception.status,404); mm.assert_not_called()
    def test_delete_validated_path_only(self):
        path=api.PREFIX+'SMS/12'
        with patch('router_api.listed',return_value=[path]), patch('router_api.mm') as mm:
            self.assertTrue(api.dispatch('POST','/v1/sms/delete',{'id':'12'})['ok'])
            mm.assert_called_once_with('-m','0','--messaging-delete-sms='+path)
    def test_ui_page_limit_and_newest_order(self):
        ids=[api.PREFIX+'SMS/'+str(i) for i in range(12)]
        with patch('router_api.listed',return_value=ids),patch('router_api.mm',return_value={}):
            result=api.dispatch('GET','/v1/sms',{'offset':0,'limit':5,'order':'newest'})
            self.assertEqual([x['id'] for x in result['messages']],['11','10','9','8','7'])
            self.assertEqual(result['next'],5)
    def test_no_second_call_while_first_active(self):
        with patch('router_api.voice',return_value={'controls_enabled':True}),patch('router_api.active_calls',return_value=['call']),patch('router_api.mm') as mm:
            with self.assertRaises(api.ApiError) as error:api.dispatch('POST','/v1/calls/dial',{'number':'1234567'})
            self.assertEqual(error.exception.status,409);mm.assert_not_called()
    def test_wrong_board_never_binds(self):
        with patch('router_setup.board',return_value='other,board'),patch('router_setup.devices') as scan:
            with self.assertRaises(RuntimeError):setup.target_device()
            scan.assert_not_called()
    def test_interface_layout_refuses_unknown_drivers(self):
        device={'product':'EG25G-QDC507','interfaces':[{'number':f'{i:02x}','class':'ff','driver':'option'} for i in range(4)]}
        with patch('router_setup.board',return_value='glinet,gl-mg1300'),patch('router_setup.devices',return_value=[device]):
            self.assertEqual(setup.target_device(),device)
            device['interfaces'][2]['driver']='cdc_ether'
            with self.assertRaises(RuntimeError):setup.target_device()
    def test_multiple_modules_refused(self):
        with patch('router_setup.board',return_value='glinet,gl-mg1300'),patch('router_setup.devices',return_value=[{},{}]):
            with self.assertRaises(RuntimeError):setup.target_device()
    def test_enables_disabled_modem_but_does_not_touch_registered_one(self):
        for state in ['disabled','registered','connected','locked']:
            with patch('router_setup.command',return_value='{"modem":{"generic":{"state":"'+state+'"}}}') as command:
                setup.enable_disabled_modem('2')
                self.assertEqual(command.call_count,2 if state=='disabled' else 1)
                if state=='disabled':self.assertEqual(command.call_args.args[0],['/usr/bin/mmcli','--timeout=5','-m','2','--enable'])

if __name__=='__main__':unittest.main()
