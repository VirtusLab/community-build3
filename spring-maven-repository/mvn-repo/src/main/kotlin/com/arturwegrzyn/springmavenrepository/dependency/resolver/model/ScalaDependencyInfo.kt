package com.arturwegrzyn.springmavenrepository.dependency.resolver.model

data class ScalaDependencyInfo(
    override val fullFilename: String,
    override val filename: String,
    override val organization: String,
    override val name: String,
    override val version: String,
    override val extension: String,
    val scalaVersion: String,
    override val type: String,
    override val mappedTo: ScalaDependencyInfo?
) : DependencyInfo(fullFilename, filename, organization, name, version, extension, type, mappedTo) {
    private fun getOrganizationPath(): String {
        return organization.replace(".", "/")
    }

    /**
     * Return path to all versions of dependency.
     * Example:
     * organization/name_scalaVersion
     * com/example/greeter_3.0.0
     */
    fun getDependencyPath(): String = "${getOrganizationPath()}/${name}_$scalaVersion"


//    fun getFilename(): String = ""

}