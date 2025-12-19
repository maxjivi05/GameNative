"""
Android-compatible stub for legendary downloader
The actual downloading will be handled by Kotlin/Android code
This module provides the interface expected by legendary.core
"""

import logging

logger = logging.getLogger('DLM-Android')


class DLManager:
    """
    Stub DLManager for Android compatibility

    The real legendary.downloader.mp.manager.DLManager uses multiprocessing
    which is not supported on Android. This stub provides the same interface
    but downloads should be handled by the Kotlin/Android layer.
    """

    def __init__(self, download_dir, base_url, cache_dir=None, status_q=None,
                 max_workers=0, update_interval=1.0, dl_timeout=10, resume_file=None,
                 max_shared_memory=1024 * 1024 * 1024, bind_ip=None):
        """Initialize stub DLManager (does nothing on Android)"""
        logger.warning('DLManager stub initialized - downloads must be handled by Kotlin')
        self.download_dir = download_dir
        self.base_url = base_url
        self.cache_dir = cache_dir

    def start(self):
        """Stub start method"""
        logger.warning('DLManager.start() called - not implemented on Android')
        pass

    def run_analysis(self, manifest, old_manifest=None, patch=False, resume=False,
                    file_prefix_filter=None, file_exclude_filter=None, file_install_tag=None):
        """Stub analysis method"""
        logger.warning('DLManager.run_analysis() called - not implemented on Android')
        return None

    def run(self):
        """Stub run method"""
        logger.warning('DLManager.run() called - not implemented on Android')
        pass

    def join(self, timeout=None):
        """Stub join method"""
        pass

    def terminate(self):
        """Stub terminate method"""
        pass
