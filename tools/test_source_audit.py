"""独立存档审计的回归测试，含陶罐内容物漏报问题的防护。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('source_audit', Path(__file__).with_name('audit-source-census.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class SourceAuditTest(unittest.TestCase):
    def inspect(self, rows, entities, status='minecraft:full'):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            regions = directory / 'server/world/dimensions/minecraft/overworld/region'
            regions.mkdir(parents=True)
            (regions / 'r.0.0.mca').touch()
            truth = directory / 'truth.json'
            truth.write_text(json.dumps(dict(complete=True, version='26.2', seed='0',
                                            dimension='minecraft:overworld', radius=5000,
                                            containers=rows)), 'utf-8')
            with patch.object(audit, 'region_chunks', return_value=iter([
                    dict(Status=status, block_entities=entities)])):
                return audit.audit(truth)

    def test_native_unopened_source_and_plain_pot_cannot_be_silently_omitted(self):
        result = self.inspect([], [
            dict(id='minecraft:brushable_block', x=1, y=2, z=3, LootTable='minecraft:archaeology/trail_ruins_rare'),
            dict(id='minecraft:decorated_pot', x=4, y=5, z=6),
        ])
        self.assertFalse(result['passed'])
        self.assertEqual(len(result['omittedNativeSources']), 2)
        self.assertEqual(len(result['unopenedTargetSources']), 1)

    def test_pot_audit_checks_contents_as_well_as_body_decorations(self):
        row = dict(pos=[1, 2, 3], kind='pot_sherd',
                   sherds=['minecraft:brick'] * 3 + ['minecraft:flow_pottery_sherd'],
                   items={'minecraft:brick': 3, 'minecraft:flow_pottery_sherd': 1, 'minecraft:string': 3})
        entity = dict(id='minecraft:decorated_pot', x=1, y=2, z=3, sherds=row['sherds'],
                      item=dict(id='minecraft:string', count=3))
        self.assertTrue(self.inspect([row], [entity])['passed'])
        entity['item']['count'] = 2
        self.assertFalse(self.inspect([row], [entity])['passed'])

    def test_reported_position_must_exist_in_a_full_chunk(self):
        row = dict(pos=[1, 2, 3], kind='container', items={'minecraft:diamond': 1})
        entity = dict(id='minecraft:chest', x=1, y=2, z=3, Items=[dict(id='minecraft:diamond', count=1)])
        self.assertTrue(self.inspect([row], [entity])['passed'])
        self.assertFalse(self.inspect([row], [entity], status='minecraft:features')['passed'])
        self.assertFalse(self.inspect([row], [])['passed'])


if __name__ == '__main__':
    unittest.main()
