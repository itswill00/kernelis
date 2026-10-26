package dev.kernelis.core.capability

/**
 * The node catalogue.
 *
 * Every path here comes from a real-world source, not guesswork:
 *  - Kernel Adiutor / SmartPack-Kernel Manager (the longest-lived open-source kernel
 *    manager; paths proven on shipping devices)
 *  - Qualcomm's public KGSL/cpufreq documentation
 *  - Linux kernel admin-guide (cpufreq, PSI, VM)
 *
 * A path being listed means KERNELIS will *probe* it. It does not mean it exists on your
 * device: the scanner reports `NotPresent` when it does not, and the UI says so.
 *
 * Paths are grouped by semantic control. Where a kernel family exposes the same control
 * under different names, all variants are listed and the first one present wins.
 */
public object NodeCatalogue {

    // ---------------------------------------------------------------- CPU frequency

    /** Per-policy (preferred) and per-CPU (fallback) directories are discovered by the scanner. */
    public const val CPU_ROOT: String = "/sys/devices/system/cpu"

    /** Cluster-wide frequency hard limits some kernels add on top of cpufreq. */
    public val CPU_HARD_LIMIT: List<String> = listOf(
        "/sys/kernel/cpufreq_hardlimit/scaling_max_freq",
        "/sys/kernel/cpufreq_hardlimit/scaling_min_freq",
        "/sys/kernel/cpufreq_hardlimit/userspace_dvfs_lock",
        "/sys/kernel/msm_cpufreq_limit/cpufreq_limit",
    )

    /** Boost enable, where the kernel exposes a boolean switch. */
    public val CPU_BOOST_ENABLE: List<String> = listOf(
        "/sys/devices/system/cpu/cpufreq/boost",
        "/sys/module/cpu_boost/parameters/input_boost_enabled",
        "/sys/module/cpu_input_boost/parameters/input_boost_enabled",
        "/sys/kernel/cpu_input_boost/enabled",
        "/sys/module/msm_performance/parameters/touchboost",
        "/sys/devices/system/cpu/intel_pstate/no_turbo",
    )

    /** Input-boost frequency list (space separated kHz), per kernel flavour. */
    public val CPU_BOOST_FREQ: List<String> = listOf(
        "/sys/module/cpu_boost/parameters/input_boost_freq",
        "/sys/module/cpu_input_boost/parameters/input_boost_freq",
        "/sys/kernel/cpu_input_boost/input_boost_freq",
    )

    /** How long the boost lasts, in milliseconds. */
    public val CPU_BOOST_MS: List<String> = listOf(
        "/sys/module/cpu_boost/parameters/input_boost_ms",
        "/sys/module/cpu_input_boost/parameters/input_boost_ms",
        "/sys/kernel/cpu_input_boost/input_boost_ms",
    )

    /** Per-cluster screen-off frequency cap (some kernels). */
    public val CPU_SCREEN_OFF_FREQ: List<String> = listOf(
        "/sys/devices/system/cpu/cpu0/cpufreq/screen_off_max_freq",
    )

    /** Hotplug drivers, all mutually exclusive. Presence = driver available. */
    public val HOTPLUG_ROOTS: List<String> = listOf(
        "/sys/kernel/msm_mpdecision/conf",
        "/sys/kernel/intelli_plug",
        "/sys/kernel/alucard_hotplug",
        "/sys/kernel/AiO_HotPlug",
        "/sys/kernel/autosmp/conf",
        "/sys/kernel/bricked_hotplug/conf",
    )

    /** MSM Performance (Qualcomm) — per-cluster min/max and touch boost. */
    public const val MSM_PERFORMANCE: String = "/sys/module/msm_performance/parameters"

    // ------------------------------------------------- MediaTek (PPM / thermal)

    /**
     * MediaTek does not let userspace write cpufreq directly on many kernels; the
     * supported path is the PPM (Performance and Power Management) procfs interface.
     * Verified against the MTK kernel source and community documentation.
     */
    public val MTK_PPM_PATHS: List<String> = listOf(
        "/proc/ppm/policy_status",
        "/proc/ppm/policy/ut_fix_freq",
        "/proc/ppm/policy/ut_fix_freq_idx",
        "/proc/ppm/policy/ptpodisable_usage",
        "/proc/ppm/policy/str_policy_status",
        "/proc/ppm/enabled",
        "/proc/ppm/root_cluster",
    )

    /** MediaTek thermal sensors expose temperatures through procfs, not thermal_zone. */
    public val MTK_THERMAL_PATHS: List<String> = listOf(
        "/proc/mtktscpu/mtktscpu_temperature",
        "/proc/mtktsbattery/mtktsbattery_temperature",
        "/proc/mtktsgpu/mtktsgpu_temperature",
        "/proc/mtktswifi/mtktswifi_temperature",
        "/proc/thermal/tscpu",
        "/proc/driver/thermal/tztscpu",
    )

    /** MediaTek EEM (Energy Efficient Model) / CPU DVFS interface. */
    public val MTK_EEM_PATHS: List<String> = listOf(
        "/proc/eem/enable",
        "/proc/cpufreq/cpufreq_oppidx",
        "/proc/cpufreq/MT_CPU_DVFS_LL/cpufreq_freq",
    )

    // ---------------------------------------------------------- Exynos / Samsung

    /** Samsung exposes its own DVFS table interface on Exynos devices. */
    public val EXYNOS_DVFS_PATHS: List<String> = listOf(
        "/sys/power/cpufreq_max_limit",
        "/sys/power/cpufreq_min_limit",
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
        "/sys/devices/platform/exynos5-dvfs/devfreq/exynos5-dvfs",
        "/sys/devices/platform/exynos5-bus-devfreq/devfreq/exynos5-bus-devfreq",
    )

    /** Samsung's sysfs class for LEDs, vibration and touch keys. */
    public const val SAMSUNG_SEC_CLASS: String = "/sys/class/sec"

    // ------------------------------------------------------------------------ GPU

    /** KGSL (Adreno) roots; the first existing directory wins. */
    public val KGSL_ROOTS: List<String> = listOf(
        "/sys/class/kgsl/kgsl-3d0",
    )

    /** Generic devfreq root. Mali/PowerVR/Xclipse all appear here with vendor names. */
    public val DEVFREQ_ROOT: String = "/sys/class/devfreq"

    /**
     * Known GPU devfreq directory names across vendors. Discovery still verifies that
     * the directory actually exposes a governor or frequency node; this list only
     * prioritises likely candidates before falling back to a full scan of the root.
     *
     *  - Adreno  : kgsl-3d0 (Qualcomm)
     *  - Mali    : *.mali, mali, mali0 (ARM: Exynos, MTK, Kirin, Tensor)
     *  - PowerVR : pvrsrvkm, sgxfreq (older Exynos/Intel Atom)
     *  - Xclipse : *.gpu (AMD RDNA-based, Exynos 2200+)
     *  - Tegra   : tegra_gpu, gpu.0
     */
    public val GPU_DEVFREQ_CANDIDATES: List<String> = listOf(
        "/sys/class/kgsl/kgsl-3d0/devfreq",
        "/sys/class/devfreq/gpufreq",
        "/sys/class/devfreq/mali",
        "/sys/class/devfreq/mali0",
        "/sys/class/devfreq/dfrgx",
        "/sys/devices/platform/omap/pvrsrvkm.0/sgxfreq",
        "/sys/kernel/tegra_gpu",
    )

    /** Substrings that identify a GPU devfreq device by its `name` attribute. */
    public val GPU_NAME_HINTS: List<String> = listOf(
        "gpu", "mali", "kgsl", "adreno", "pvrsrv", "sgxfreq", "dfrgx", "xclipse", "tegra",
    )

    /** GPU files relative to a discovered root. */
    public val GPU_FILES: List<String> = listOf(
        "devfreq/governor",
        "devfreq/available_governors",
        "devfreq/available_frequencies",
        "devfreq/min_freq",
        "devfreq/max_freq",
        "devfreq/cur_freq",
        "devfreq/adrenoboost",
        "gpu_available_frequencies",
        "gpuclk",
        "max_gpuclk",
        "min_clock_mhz",
        "max_pwrlevel",
        "min_pwrlevel",
        "default_pwrlevel",
        "gpubusy",
        "gpu_busy_percentage",
        "pwrscale/trustzone/governor",
        "pwrscale/awake_timeout_ns",
    )

    // ------------------------------------------------------------------------- I/O

    public val IO_QUEUE_FILES: List<String> = listOf(
        "scheduler",
        "read_ahead_kb",
        "nr_requests",
        "nomerges",
        "rotational",
        "iostats",
        "add_random",
        "rq_affinity",
        "iosched/quantum",
        "iosched/fifo_batch",
        "iosched/writes_starved",
        "iosched/front_merges",
    )

    // -------------------------------------------------------------------- Memory/VM

    /**
     * VM tunables from `/proc/sys/vm`, taken from Kernel Adiutor's list plus the kernel
     * admin guide. Only those with a sane range are offered for writing; the rest are
     * read-only diagnostics.
     */
    public val VM_TUNABLES: List<VmTunable> = listOf(
        VmTunable("swappiness", "/proc/sys/vm/swappiness", 0, 200, "Swappiness"),
        VmTunable("dirty_ratio", "/proc/sys/vm/dirty_ratio", 1, 100, "Dirty ratio", "%"),
        VmTunable("dirty_background_ratio", "/proc/sys/vm/dirty_background_ratio", 1, 100, "Dirty background ratio", "%"),
        VmTunable("dirty_expire_centisecs", "/proc/sys/vm/dirty_expire_centisecs", 0, 30_000, "Dirty expire", "cs"),
        VmTunable("dirty_writeback_centisecs", "/proc/sys/vm/dirty_writeback_centisecs", 0, 30_000, "Dirty writeback", "cs"),
        VmTunable("vfs_cache_pressure", "/proc/sys/vm/vfs_cache_pressure", 1, 1000, "VFS cache pressure"),
        VmTunable("min_free_kbytes", "/proc/sys/vm/min_free_kbytes", 0, 1_048_576, "Min free", "kB"),
        VmTunable("extra_free_kbytes", "/proc/sys/vm/extra_free_kbytes", 0, 1_048_576, "Extra free", "kB"),
        VmTunable("watermark_scale_factor", "/proc/sys/vm/watermark_scale_factor", 0, 3000, "Watermark scale"),
        VmTunable("overcommit_memory", "/proc/sys/vm/overcommit_memory", 0, 2, "Overcommit mode"),
        VmTunable("overcommit_ratio", "/proc/sys/vm/overcommit_ratio", 0, 100, "Overcommit ratio", "%"),
        VmTunable("max_map_count", "/proc/sys/vm/max_map_count", 0, 4_000_000, "Max map count"),
        VmTunable("oom_dump_tasks", "/proc/sys/vm/oom_dump_tasks", 0, 1, "OOM dump tasks"),
        VmTunable("panic_on_oom", "/proc/sys/vm/panic_on_oom", 0, 2, "Panic on OOM"),
        VmTunable("laptop_mode", "/proc/sys/vm/laptop_mode", 0, 5, "Laptop mode"),
        VmTunable("stat_interval", "/proc/sys/vm/stat_interval", 0, 60, "Stat interval", "s"),
    )

    /** Low-memory-killer (in-kernel, pre-4.12 kernels). */
    public val LMK_PARAMS: List<String> = listOf(
        "/sys/module/lowmemorykiller/parameters/minfree",
        "/sys/module/lowmemorykiller/parameters/adj",
        "/sys/module/lowmemorykiller/parameters/enable_adaptive_lmk",
        "/sys/module/lowmemorykiller/parameters/lmk_fast_run",
        "/sys/module/lowmemorykiller/parameters/swap_wait",
        "/sys/module/lowmemorykiller/parameters/swap_wait_percent",
    )

    /** Kernel same-page merging. */
    public val KSM_ROOTS: List<String> = listOf("/sys/kernel/mm/ksm", "/sys/kernel/mm/uksm")

    // ------------------------------------------------------------------ Scheduler

    /** stune / cpuctl groups, in preference order. */
    public val STUNE_ROOTS: List<String> = listOf("/dev/stune", "/dev/cpuctl")

    public val STUNE_GROUPS: List<String> = listOf("top-app", "foreground", "background", "root")

    public val UCLAMP_FILES: List<String> = listOf(
        "/proc/sys/kernel/sched_util_clamp_min",
        "/proc/sys/kernel/sched_util_clamp_max",
        "/proc/sys/kernel/sched_util_clamp_min_rt_default",
    )

    // ------------------------------------------------------------------- Thermal

    public const val THERMAL_ROOT: String = "/sys/class/thermal"

    /** MSM thermal (Sultanxda / Qualcomm), both module and sysfs-kernel variants. */
    public val MSM_THERMAL_ROOTS: List<String> = listOf(
        "/sys/module/msm_thermal/parameters",
        "/sys/kernel/msm_thermal/conf",
        "/sys/kernel/msm_thermal",
    )

    public val MSM_THERMAL_FILES: List<String> = listOf(
        "enabled",
        "temperature_threshold",
        "temp_threshold",
        "freq_mitig_temp_degc",
        "hotplug_temp_degC",
        "core_temp_limit_degC",
        "freq_mitig_control_mask",
        "freq_control_mask",
        "core_control_mask",
        "poll_ms",
        "sensor_temp",
        "limit_temp_degC",
    )

    /** Simple MSM thermal (Sultanxda) exposes its own module tree. */
    public const val SIMPLE_THERMAL: String = "/sys/module/simple_thermal/parameters"

    // ------------------------------------------------------------------- Wakelocks

    public val WAKELOCK_PARAMS: List<String> = listOf(
        "/sys/module/wakeup/parameters",
        "/sys/devices/virtual/misc/boeffla_wakelock_blocker",
        "/sys/module/bcmdhd/parameters/wl_divide",
        "/sys/module/bcmdhd/parameters/wlctrl_divide",
    )

    // --------------------------------------------------------------------- Screen

    public val KCAL_ROOTS: List<String> = listOf(
        "/sys/devices/platform/kcal_ctrl.0",
        "/sys/class/graphics/fb0",
        "/sys/class/misc/colorcontrol",
    )

    public val KCAL_FILES: List<String> = listOf(
        "kcal",
        "kcal_cont",
        "kcal_hue",
        "kcal_sat",
        "kcal_val",
        "kcal_min",
        "kcal_invert",
        "rgb",
        "srgb",
        "hbm",
    )

    // --------------------------------------------------------------------- Sound

    public val SOUND_ROOTS: List<String> = listOf(
        "/sys/kernel/sound_control_3",
        "/sys/kernel/sound_control",
        "/sys/module/snd_soc_msm/parameters",
    )

    // ------------------------------------------------------------------- Charging

    public val CHARGE_ROOTS: List<String> = listOf(
        "/sys/kernel/fast_charge",
        "/sys/kernel/charge_levels",
        "/sys/class/power_supply/battery",
    )

    public val CHARGE_FILES: List<String> = listOf(
        "force_fast_charge",
        "fast_charge",
        "charge_level",
        "input_current_limit",
        "constant_charge_current_max",
        "voltage_max",
        "battery_charging_enabled",
        "charge_enabled",
    )

    // ------------------------------------------------------------------ Misc kernel

    public val MISC_FILES: List<String> = listOf(
        "/sys/kernel/dyn_fsync/Dyn_fsync_active",
        "/sys/kernel/logger_mode/logger_mode",
        "/sys/kernel/printk_mode/printk_mode",
        "/sys/kernel/power_suspend/power_suspend_mode",
        "/sys/kernel/sched/arch_power",
        "/sys/kernel/sched/gentle_fair_sleepers",
        "/proc/sys/kernel/printk_devkmsg",
        "/proc/sys/net/ipv4/tcp_congestion_control",
        "/proc/sys/fs/leases-enable",
        "/proc/sys/fs/lease-break-time",
    )

    // ------------------------------------------------------------------- ZRAM

    public val ZRAM_FILES: List<String> = listOf(
        "disksize",
        "comp_algorithm",
        "mem_used_total",
        "mem_limit",
        "orig_data_size",
        "compr_data_size",
        "mem_used_max",
        "reset",
    )
}

/** A `/proc/sys/vm` tunable with its sane write range. */
public data class VmTunable(
    val key: String,
    val path: String,
    val min: Long,
    val max: Long,
    val label: String,
    val units: String? = null,
)
