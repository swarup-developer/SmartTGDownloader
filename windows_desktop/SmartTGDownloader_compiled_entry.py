"""Entry point for the compiled build.

PyInstaller analyses this file; all real logic lives in the Cython-compiled
smarttgdownloader package staged next to it (utils/worker/ui .pyd modules),
so no readable Python source for the app modules ships in the final exe.
"""

import os
import sys

# Ensure the compiled package (staged alongside this entry script) is importable.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from smarttgdownloader.ui import run_app

if __name__ == "__main__":
    run_app()
