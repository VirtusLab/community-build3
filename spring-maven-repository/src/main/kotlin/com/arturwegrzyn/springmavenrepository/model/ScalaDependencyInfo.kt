package com.arturwegrzyn.springmavenrepository.model

class ScalaDependencyInfo(
        val organization: String,
        val name: String,
        val filename: String,
        val scalaVersion: String,
        private val nameAndScalaVersion: String,
        val version: String,
        val jarType: String,
        val extension: String,
        val mappedTo: ScalaDependencyInfo?
) {
    private fun getOrganizationPath(): String {
        return organization.replace(".", "/")
    }

    /**
     * Return path to all versions of dependency.
     * Example:
     * organization/name_scalaVersion
     * com/example/greeter_3.0.0
     */
    fun getDependencyPath(): String {
        return getOrganizationPath() + "/" + nameAndScalaVersion
    }

}