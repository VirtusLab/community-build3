package com.arturwegrzyn.springmavenrepository.dependency.resolver.model

data class JavaDependencyInfo(
    override val fullFilename: String,
    override val filename: String,
    override val organization: String,
    override val name: String,
    override val version: String,
    override val extension: String,
    override val mappedTo: JavaDependencyInfo?,
    val jarType: String
) : DependencyInfo(fullFilename, filename, organization, name, version, extension, mappedTo) {
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
        return getOrganizationPath() + "/" + name
    }

}