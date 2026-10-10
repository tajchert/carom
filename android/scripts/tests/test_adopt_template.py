import argparse
import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

spec = importlib.util.spec_from_file_location('adopt', Path(__file__).parents[1] / 'adopt-template.py')
adopt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adopt)


class AdoptionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.identity = dict(package='com.demo.sample', app_name='Sample App', host='tasks.demo.com',
                             scheme='demo-sample', symbol_prefix='Sample', project_name='android-sample-compose', database='sample-database')
        self.write('.template-identity.json', json.dumps(self.identity))
        self.write('app/src/main/kotlin/com/demo/sample/SampleApplication.kt', 'package com.demo.sample\nclass SampleApplication')
        self.write('app/src/main/res/values/strings.xml', '<resources><string name="app_name">Sample App</string></resources>')
        self.write('core/database/schemas/com.demo.sample.core.database.TaskDatabase/2.json', '{"version":2}')
        self.write('app/src/main/generated/baselineProfiles/baseline-prof.txt', 'Lcom/demo/sample/SampleApplication;')
        self.write('app/src/screenshotTestDebug/reference/old.png', 'old generated pixels')
        self.write('app/build/cache.txt', 'com.demo.sample')
        self.args = argparse.Namespace(package='org.example.notes', app_name="Notes & Ideas", host='notes.example.org',
            scheme='notes-app', symbol_prefix='Notes', project_name='notes', apply=False)

    def write(self, path, text):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)

    def run_adoption(self):
        with contextlib.redirect_stdout(io.StringIO()):
            adopt.adopt(self.root, self.args)

    def test_preview_changes_nothing(self):
        before = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        self.run_adoption()
        self.assertEqual(before, {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob('*') if p.is_file()})

    def test_rename_moves_sources_schemas_and_invalidates_generated_artifacts(self):
        self.args.apply = True
        self.run_adoption()
        self.assertEqual('package org.example.notes\nclass NotesApplication',
            (self.root/'app/src/main/kotlin/org/example/notes/NotesApplication.kt').read_text())
        self.assertTrue((self.root/'core/database/schemas/org.example.notes.core.database.TaskDatabase/2.json').exists())
        self.assertIn('Notes &amp; Ideas', (self.root/'app/src/main/res/values/strings.xml').read_text())
        self.assertFalse((self.root/'app/src/main/generated/baselineProfiles/baseline-prof.txt').exists())
        self.assertFalse((self.root/'app/src/screenshotTestDebug/reference/old.png').exists())
        self.assertEqual('com.demo.sample', (self.root/'app/build/cache.txt').read_text())
        before = (self.root/'.template-identity.json').read_text()
        self.run_adoption()
        self.assertEqual(before, (self.root/'.template-identity.json').read_text())

    def test_invalid_identity_does_not_mutate_files(self):
        self.args.package = 'org.class.notes'
        self.args.apply = True
        with self.assertRaises(ValueError):
            self.run_adoption()
        self.assertTrue((self.root/'app/src/main/kotlin/com/demo/sample/SampleApplication.kt').exists())

    def test_parent_file_is_rejected_before_any_mutation(self):
        self.write('a.txt', 'com.demo.sample')
        self.write('app/src/main/kotlin/org', 'blocking file')
        self.args.apply = True
        with self.assertRaises(ValueError):
            self.run_adoption()
        self.assertEqual('com.demo.sample', (self.root/'a.txt').read_text())
        self.assertEqual(self.identity, json.loads((self.root/'.template-identity.json').read_text()))

    def test_java_keyword_package_is_rejected(self):
        self.args.package = 'org.new.notes'
        self.args.apply = True
        with self.assertRaises(ValueError):
            self.run_adoption()

    def test_inserted_identity_values_are_not_replaced_again(self):
        self.args.package = 'tasks.demo.com'
        self.args.apply = True
        self.run_adoption()
        self.assertEqual('package tasks.demo.com\nclass NotesApplication',
            (self.root/'app/src/main/kotlin/tasks/demo/com/NotesApplication.kt').read_text())

    def test_uppercase_deep_link_fixtures_follow_the_new_host_and_scheme(self):
        self.write('links.txt', 'HTTPS://TASKS.DEMO.COM/tasks DEMO-SAMPLE://tasks')
        self.args.apply = True
        self.run_adoption()
        self.assertEqual('HTTPS://NOTES.EXAMPLE.ORG/tasks NOTES-APP://tasks', (self.root/'links.txt').read_text())

    def test_collision_is_detected_before_writes(self):
        self.write('app/src/main/kotlin/org/example/notes/NotesApplication.kt', 'existing')
        self.args.apply = True
        with self.assertRaises(ValueError):
            self.run_adoption()
        self.assertEqual('existing', (self.root/'app/src/main/kotlin/org/example/notes/NotesApplication.kt').read_text())
        self.assertEqual(self.identity, json.loads((self.root/'.template-identity.json').read_text()))

    def test_conflicting_identity_tokens_are_rejected_before_writes(self):
        self.identity['app_name'] = self.identity['symbol_prefix']
        self.write('.template-identity.json', json.dumps(self.identity))
        self.args.apply = True
        with self.assertRaises(ValueError):
            self.run_adoption()
        self.assertTrue((self.root/'app/src/main/kotlin/com/demo/sample/SampleApplication.kt').exists())

    def test_symlink_destination_parent_is_rejected(self):
        (self.root/'app/src/main/kotlin/org').symlink_to(self.root/'app/build', target_is_directory=True)
        self.args.apply = True
        with self.assertRaises(ValueError):
            self.run_adoption()
        self.assertTrue((self.root/'app/src/main/kotlin/com/demo/sample/SampleApplication.kt').exists())

    def test_write_failure_restores_sources_generated_artifacts_and_identity(self):
        before = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        write_text = Path.write_text
        def failing_write(path, *args, **kwargs):
            if path == self.root/'.template-identity.json':
                raise OSError('simulated disk failure')
            return write_text(path, *args, **kwargs)
        self.args.apply = True
        with patch.object(Path, 'write_text', failing_write), self.assertRaises(OSError):
            self.run_adoption()
        self.assertEqual(before, {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob('*') if p.is_file()})


if __name__ == '__main__':
    unittest.main()
