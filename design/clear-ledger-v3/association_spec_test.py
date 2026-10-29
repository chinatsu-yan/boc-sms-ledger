"""Executable design specification only; not wired into the Android APK."""
from dataclasses import dataclass,replace
from datetime import date
from decimal import Decimal as D
import unittest
@dataclass(frozen=True)
class Event:
 id:int
 when:date
 amount:D=D('49.00')
 card:str='credit:4826'
 currency:str='USD'
 merchant:str|None='PINE_ISLE'
 scope:str='demo:sim1'
 eligible:bool=True

def candidates(adjustment,purchases,used,other_amounts=False):
 return sorted([p for p in purchases if p.eligible and p.card==adjustment.card and p.currency==adjustment.currency and p.scope==adjustment.scope and p.when<=adjustment.when and p.amount-used.get(p.id,D(0))>=adjustment.amount and (other_amounts or p.amount==adjustment.amount)],key=lambda p:(p.when,p.id))

def automatic(adjustment,purchases,used,linked=frozenset(),blocked=frozenset()):
 if not adjustment.eligible or adjustment.id in linked or adjustment.id in blocked or not adjustment.merchant:return None
 return next((p.id for p in candidates(adjustment,purchases,used) if p.merchant==adjustment.merchant and p.id not in blocked),None)

class AssociationRuleTests(unittest.TestCase):
 def setUp(self):
  self.a=Event(100,date(2026,6,20));self.old=Event(1,date(2026,6,6));self.new=Event(2,date(2026,6,14))
 def test_same_merchant_amount_earliest(self):self.assertEqual(automatic(self.a,[self.new,self.old],{}),1)
 def test_stable_tie(self):self.assertEqual(automatic(self.a,[replace(self.old,id=2),self.old],{}),1)
 def test_skip_effectively_canceled(self):self.assertEqual(automatic(self.a,[self.old,self.new],{1:D('49')}),2)
 def test_no_merchant_manual_only(self):
  a=replace(self.a,merchant=None);self.assertIsNone(automatic(a,[self.old],{}));self.assertEqual(candidates(a,[self.old],{})[0].id,1)
 def test_wrong_card_currency_scope_or_later_excluded(self):
  for p in [replace(self.old,card='credit:9999'),replace(self.old,currency='CNY'),replace(self.old,scope='demo:sim2'),replace(self.old,when=date(2026,6,21))]:self.assertIsNone(automatic(self.a,[p],{}))
 def test_partial_manual_option(self):
  a=replace(self.a,amount=D('9'));self.assertIsNone(automatic(a,[self.old],{}));self.assertEqual(candidates(a,[self.old],{},True)[0].id,1)
 def test_cumulative_not_over(self):self.assertEqual(candidates(self.a,[self.old],{1:D('0.01')}),[])
 def test_idempotency_and_unlink_veto(self):
  self.assertIsNone(automatic(self.a,[self.old],{},linked={100}));self.assertIsNone(automatic(self.a,[self.old],{},blocked={100}))
if __name__=='__main__':unittest.main()
