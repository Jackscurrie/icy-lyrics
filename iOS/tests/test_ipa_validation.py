"""Architecture/platform rejection tests; no fake file is presented as a real IPA."""
from pathlib import Path
import struct
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"scripts"))
from package_ipa import macho, validate_dependencies, validate_minimum_os

class MachOValidation(unittest.TestCase):
    def inspect(self,cpu=0x0100000C,platform=2,encrypted=0):
        commands=struct.pack("<6I",0x32,24,platform,16<<16,26<<16,0)+struct.pack("<6I",0x2C,24,0,0,encrypted,0)
        data=struct.pack("<8I",0xFEEDFACF,cpu,0,2,2,len(commands),0,0)+commands
        with tempfile.TemporaryDirectory() as directory:
            file=Path(directory)/"fixture"
            file.write_bytes(data)
            return macho(file)
    def test_device_arm64(self): self.assertEqual("iOS device",self.inspect()["platform"])
    def test_device_minimum_os_is_read_from_load_command(self):
        self.assertEqual("16.0.0",self.inspect()["minimumOS"])
    def test_arm64_simulator_is_rejected(self):
        with self.assertRaisesRegex(ValueError,"Not an iOS device"): self.inspect(platform=7)
    def test_x86_simulator_is_rejected(self):
        with self.assertRaisesRegex(ValueError,"Expected arm64"): self.inspect(cpu=0x01000007,platform=7)
    def test_encrypted_app_store_binary_is_rejected(self):
        with self.assertRaisesRegex(ValueError,"encrypted"): self.inspect(encrypted=1)
    def test_non_macho_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            file=Path(directory)/"fixture"
            file.write_bytes(b"not a binary")
            with self.assertRaisesRegex(ValueError,"Mach-O"): macho(file)

    def inspect_commands(self,commands,payload=b"",count=None):
        command_data=b"".join(commands)
        data=struct.pack("<8I",0xFEEDFACF,0x0100000C,0,2,len(commands) if count is None else count,len(command_data),0,0)+command_data+payload
        with tempfile.TemporaryDirectory() as directory:
            file=Path(directory)/"fixture"
            file.write_bytes(data)
            return macho(file)

    def test_truncated_binary_payload_is_rejected_even_when_commands_are_intact(self):
        build=struct.pack("<6I",0x32,24,2,16<<16,26<<16,0)
        segment=struct.pack("<2I16s4Q4I",0x19,72,b"__TEXT",0,4096,0,4096,7,5,0,0)
        with self.assertRaisesRegex(ValueError,"segment payload"):
            self.inspect_commands([build,segment])

    def test_missing_linkedit_payload_is_rejected(self):
        build=struct.pack("<6I",0x32,24,2,16<<16,26<<16,0)
        with self.assertRaisesRegex(ValueError,"link-edit payload"):
            self.inspect_commands([build,struct.pack("<4I",0x1D,16,4096,100)])

    def test_conflicting_simulator_and_legacy_device_markers_are_rejected(self):
        build=struct.pack("<6I",0x32,24,7,16<<16,26<<16,0)
        legacy=struct.pack("<4I",0x25,16,16<<16,26<<16)
        with self.assertRaisesRegex(ValueError,"Not an iOS device"):
            self.inspect_commands([build,legacy])

    def test_unparsed_load_commands_are_rejected(self):
        build=struct.pack("<6I",0x32,24,2,16<<16,26<<16,0)
        with self.assertRaisesRegex(ValueError,"command count"):
            self.inspect_commands([build,build],count=1)

    def test_loader_runpaths_are_read_from_the_binary(self):
        build=struct.pack("<6I",0x32,24,2,16<<16,26<<16,0)
        path=b"@executable_path/Frameworks\0"
        size=(12+len(path)+7)//8*8
        rpath=struct.pack("<3I",0x8000001C,size,12)+path+b"\0"*(size-12-len(path))
        self.assertEqual(["@executable_path/Frameworks"],self.inspect_commands([build,rpath])["rpaths"])

    def test_legacy_minimum_os_is_read(self):
        legacy=struct.pack("<4I",0x25,16,(15<<16)+(2<<8)+1,26<<16)
        self.assertEqual("15.2.1",self.inspect_commands([legacy])["minimumOS"])

    def test_conflicting_minimum_os_markers_are_rejected(self):
        build=struct.pack("<6I",0x32,24,2,16<<16,26<<16,0)
        legacy=struct.pack("<4I",0x25,16,17<<16,26<<16)
        with self.assertRaisesRegex(ValueError,"Conflicting Mach-O minimum"):
            self.inspect_commands([build,legacy])

class MinimumOSValidation(unittest.TestCase):
    def test_ios16_app_can_embed_framework_with_older_minimum(self):
        validate_minimum_os("16.0",{"IcyLyrics":{"minimumOS":"16.0.0"},"SDK":{"minimumOS":"13.0.0"}})

    def test_raising_or_lowering_promised_deployment_minimum_is_rejected(self):
        for declared in ("15.0","16.1","17.0","26.0"):
            with self.subTest(declared=declared),self.assertRaisesRegex(ValueError,"promised iOS 16.0"):
                validate_minimum_os(declared,{})

    def test_framework_with_newer_minimum_cannot_pass_via_app_plist(self):
        with self.assertRaisesRegex(ValueError,"requires newer iOS"):
            validate_minimum_os("16.0",{"SDK":{"minimumOS":"16.0.1"}})

class DylibClosureValidation(unittest.TestCase):
    def binary(self,dependencies=(),rpaths=()):
        return {"dependencies":list(dependencies),"rpaths":list(rpaths)}

    def test_embedded_framework_is_resolved_using_the_actual_main_runpath(self):
        validate_dependencies({
            "IcyLyrics":self.binary(["@rpath/SpotifyiOS.framework/SpotifyiOS"],["@executable_path/Frameworks"]),
            "Frameworks/SpotifyiOS.framework/SpotifyiOS":self.binary(["/usr/lib/libSystem.B.dylib"]),
        },"IcyLyrics")

    def test_present_framework_without_a_matching_runpath_is_rejected(self):
        with self.assertRaisesRegex(ValueError,"runpath"):
            validate_dependencies({
                "IcyLyrics":self.binary(["@rpath/SpotifyiOS.framework/SpotifyiOS"]),
                "Frameworks/SpotifyiOS.framework/SpotifyiOS":self.binary(),
            },"IcyLyrics")

    def test_bundle_file_that_was_not_validated_as_a_binary_cannot_satisfy_a_dependency(self):
        with self.assertRaisesRegex(ValueError,"Missing embedded dependency"):
            validate_dependencies({"IcyLyrics":self.binary(["@rpath/fake.dylib"],["@executable_path/Frameworks"])},"IcyLyrics")

    def test_loader_relative_dependency_is_resolved_from_its_own_framework(self):
        validate_dependencies({
            "IcyLyrics":self.binary(),
            "Frameworks/One.framework/One":self.binary(["@loader_path/../Two.framework/Two"]),
            "Frameworks/Two.framework/Two":self.binary(),
        },"IcyLyrics")

    def test_loader_path_cannot_escape_the_bundle(self):
        with self.assertRaisesRegex(ValueError,"escapes"):
            validate_dependencies({"IcyLyrics":self.binary(["@loader_path/../outside.dylib"])},"IcyLyrics")

if __name__=="__main__": unittest.main()
