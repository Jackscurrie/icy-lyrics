"""Check the generated project graph and staged paths on every build host."""
from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"scripts"))
import generate_xcode_project as project

class XcodeProjectValidation(unittest.TestCase):
    def test_source_ids_use_the_same_posix_paths_on_windows_and_macos(self):
        references={value["path"]:key for key,value in project.objects.items()
                    if value["isa"]=="PBXFileReference" and value.get("lastKnownFileType")=="sourcecode.swift"}
        sources={path.relative_to(project.APP).as_posix()
                 for directory in ("IcyLyrics","IcyLyricsTests","IcyLyricsUITests")
                 for path in (project.APP/directory).rglob("*.swift")}
        self.assertEqual(sources,set(references))
        for path,identifier in references.items():
            self.assertNotIn("\\",path)
            self.assertEqual(project.uid("source:"+path),identifier)

    def test_every_project_object_reference_resolves(self):
        def visit(value):
            if isinstance(value,project.Ref): self.assertIn(value,project.objects)
            elif isinstance(value,dict):
                for item in value.values(): visit(item)
            elif isinstance(value,list):
                for item in value: visit(item)
        for value in project.objects.values(): visit(value)

    def test_framework_links_match_staged_files_and_only_dynamic_spotify_is_embedded(self):
        self.assertEqual("Frameworks/IcyShared.framework",project.objects[project.shared]["path"])
        self.assertEqual("Frameworks/SpotifyiOS.xcframework",project.objects[project.spotify]["path"])
        links=project.objects[project.uid("links:IcyLyrics")]["files"]
        self.assertEqual({project.shared,project.spotify},{project.objects[key]["fileRef"] for key in links})
        embeds=project.objects[project.uid("embed")]["files"]
        self.assertEqual([project.spotify],[project.objects[key]["fileRef"] for key in embeds])

    def test_both_test_bundles_are_in_the_scheme_and_depend_on_the_app(self):
        scheme=ET.fromstring(project.scheme_output)
        names={element.attrib["BlueprintName"] for element in scheme.findall("./TestAction/Testables/TestableReference/BuildableReference")}
        self.assertEqual({"IcyLyricsTests","IcyLyricsUITests"},names)
        for name in names:
            target=project.objects[project.uid("target:"+name)]
            self.assertEqual([project.app_target],[project.objects[key]["target"] for key in target["dependencies"]])

    def test_committed_project_matches_deterministic_generation(self):
        project.main(["--check"])

if __name__=="__main__": unittest.main()
