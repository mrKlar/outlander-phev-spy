# Environnement de développement contenu — sourcer depuis n'importe quel shell :
#   source scripts/dev-env.sh
# Chemins absolus calculés depuis l'emplacement de ce script (jamais $PWD).

# Résolution de la racine du dépôt (parent de scripts/)
_dev_env_root="$(cd "$(dirname "${BASH_SOURCE[0]:-${ZSH_VERSION:+$_}}")/.." && pwd -P)" 2>/dev/null || _dev_env_root=""
if [ -z "${_dev_env_root}" ]; then
  echo "dev-env.sh : impossible de résoudre la racine du dépôt" >&2
  return 1 2>/dev/null || exit 1
fi

export JAVA_HOME="${_dev_env_root}/.toolchain/jdk"
export ANDROID_HOME="${_dev_env_root}/.toolchain/android-sdk"
export ANDROID_SDK_ROOT="${ANDROID_HOME}"
export GRADLE_USER_HOME="${_dev_env_root}/.toolchain/gradle-home"
export ANDROID_USER_HOME="${_dev_env_root}/.toolchain/android-user-home"
export PATH="${JAVA_HOME}/bin:${_dev_env_root}/.toolchain/gradle/gradle-9.7.1/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

unset _dev_env_root
