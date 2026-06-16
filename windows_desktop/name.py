import wx
import os
import re
import subprocess

class MyFrame(wx.Frame):
    def __init__(self):
        super().__init__(None, title="Smart Rename + Convert (FFmpeg)", size=(450, 220))

        panel = wx.Panel(self)

        self.text = wx.TextCtrl(panel)
        button = wx.Button(panel, label="Process Episodes")

        sizer = wx.BoxSizer(wx.VERTICAL)
        sizer.Add(self.text, 0, wx.ALL | wx.EXPAND, 10)
        sizer.Add(button, 0, wx.ALL | wx.CENTER, 10)

        panel.SetSizer(sizer)

        button.Bind(wx.EVT_BUTTON, self.process_files)

    # --------------------------------------------
    # Convert M4A to MP3 using FFmpeg (safe mode)
    # --------------------------------------------
    def convert_m4a_to_mp3(self, filepath):
        full_input = os.path.abspath(filepath)
        base_name = os.path.splitext(full_input)[0]
        mp3_file = base_name + ".mp3"

        if os.path.exists(mp3_file):
            print(f"MP3 already exists, skipping: {mp3_file}")
            return mp3_file

        command = [
            "ffmpeg",
            "-y",
            "-i", full_input,
            "-map", "0:a:0",
            "-codec:a", "libmp3lame",
            "-qscale:a", "2",
            mp3_file
        ]

        try:
            result = subprocess.run(
                command,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True
            )

            if result.returncode != 0:
                print("FFmpeg Error:\n", result.stderr)
                return None

            os.remove(full_input)
            print(f"Converted: {filepath}")
            return mp3_file

        except Exception as e:
            print("Conversion Exception:", e)
            return None

    # --------------------------------------------
    # Main Processing
    # --------------------------------------------
    def process_files(self, event):
        new_name = self.text.GetValue().strip()

        if not new_name:
            wx.MessageBox("Enter a series name first.", "Error")
            return

        folder = os.getcwd()
        auto_counter = 1

        # Step 1: Convert all M4A first
        for file in os.listdir(folder):
            if file.lower().endswith(".m4a"):
                self.convert_m4a_to_mp3(file)

        # Step 2: Rename MP3 and MP4
        for file in os.listdir(folder):
            if file.lower().endswith((".mp3", ".mp4")):
                old_path = os.path.join(folder, file)
                name, ext = os.path.splitext(file)

                match = re.search(r'(\d+)', name)

                if match:
                    episode_number = match.group(1)
                else:
                    episode_number = str(auto_counter)
                    auto_counter += 1

                new_filename = f"{new_name} {episode_number}{ext}"
                new_path = os.path.join(folder, new_filename)

                # Prevent overwrite
                if os.path.exists(new_path):
                    print(f"Skipping (already exists): {new_filename}")
                    continue

                os.rename(old_path, new_path)
                print(f"Renamed: {file} → {new_filename}")

        wx.MessageBox("Conversion + Renaming Completed Successfully.", "Done")


app = wx.App()
frame = MyFrame()
frame.Show()
app.MainLoop()