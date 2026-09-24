import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import json

spec = importlib.util.spec_from_file_location('exporter', Path(__file__).with_name('export-excluded-roads.py'))
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)

class ExportTest(unittest.TestCase):
    def test_geometry_direct_and_propagated_causes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'source.osm'
            source.write_text('''<osm><node id="1" lon="127" lat="37"/><node id="2" lon="127.01" lat="37"/>
              <way id="10"><nd ref="1"/><nd ref="2"/></way><way id="20"><nd ref="1"/><nd ref="2"/></way>
              <relation id="100"><member type="way" ref="10" role="to"/><member type="node" ref="1" role="via"/></relation>
              <relation id="101"><member type="way" ref="20" role="from"/></relation></osm>''')
            audit = root / 'audit.tsv'
            audit.write_text('kind\tid\treason\nrelation\t100\t[MISSING_FROM]\nrelation\t101\tquarantined_member\nexcluded_accepted_way\t10\tquarantine\nexcluded_accepted_way\t20\tquarantine\n')
            output = root / 'output.json'
            def extract(args, **kwargs):
                Path(args[-1]).write_text(source.read_text())
            with patch.object(exporter.subprocess, 'run', side_effect=extract):
                exporter.export(source, audit, output)
            data = json.loads(output.read_text())['features']
            self.assertEqual(['direct', 'propagated'], [f['properties']['category'] for f in data])
            self.assertEqual(['1'], data[0]['properties']['nodeIds'])
            self.assertEqual(['100'], data[0]['properties']['relationIds'])
            self.assertEqual([[127,37],[127.01,37]], data[0]['geometry']['coordinates'])
            # Missing source geometry must reject the export, not silently omit it.
            audit.write_text(audit.read_text()+'excluded_accepted_way\t30\tquarantine\n')
            with patch.object(exporter.subprocess, 'run', side_effect=extract):
                with self.assertRaises(ValueError):
                    exporter.export(source, audit, root / 'missing.json')

if __name__ == '__main__': unittest.main()
